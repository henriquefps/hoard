package items

import (
	"sort"

	"github.com/henriquefps/hoard/server/internal/media"
)

// Source is one place an item's files were found: a NAS group or an uploaded original.
type Source struct {
	Type       string // nas | backup
	FolderID   string
	FolderName string
	Members    []Member
}

// Location is where one copy of a file lives.
type Location struct {
	Source     string `json:"source"` // nas | backup
	FolderID   string `json:"folderId,omitempty"`
	FolderName string `json:"folderName,omitempty"`
	Path       string `json:"path,omitempty"`       // nas: relative to the folder, '/'-separated
	ModifiedAt int64  `json:"modifiedAt,omitempty"` // nas: the file's mtime, Unix ms
	SHA256     string `json:"sha256,omitempty"`     // backup: the blob
}

// File is one member file of an item.
type File struct {
	Fingerprint string     `json:"fingerprint"`
	Role        string     `json:"role"` // raw | rendered | video | sidecar
	Name        string     `json:"name,omitempty"`
	Format      string     `json:"format,omitempty"`
	Size        int64      `json:"size"`
	Locations   []Location `json:"locations"`
}

// Motion says where a motion photo's video is.
type Motion struct {
	Source         string `json:"source"`      // file (a video next to the still) | embedded (inside the still)
	Fingerprint    string `json:"fingerprint"` // file: the video's; embedded: the still's
	Format         string `json:"format,omitempty"`
	Offset         int64  `json:"offset,omitempty"`
	Length         int64  `json:"length,omitempty"`
	PresentationUs *int64 `json:"presentationUs,omitempty"`
}

// Data is an item's state as served (without id, rev and the timestamps the store adds).
type Data struct {
	Kind            string         `json:"kind"`
	MainFingerprint string         `json:"mainFingerprint"`
	SortTime        int64          `json:"sortTime"`
	Capture         *media.Capture `json:"capture,omitempty"`
	Make            string         `json:"make,omitempty"`
	Model           string         `json:"model,omitempty"`
	LensMake        string         `json:"lensMake,omitempty"`
	Lens            string         `json:"lens,omitempty"`
	GPS             *media.GPS     `json:"gps,omitempty"`
	Width           int            `json:"width,omitempty"`
	Height          int            `json:"height,omitempty"`
	Orientation     int            `json:"orientation,omitempty"`
	Video           *media.Video   `json:"video,omitempty"`
	Motion          *Motion        `json:"motion,omitempty"`
	Files           []File         `json:"files"`
}

// Build computes an item from its sources and the stored metadata of its files (ADR 0014 "Items"). ok is false when
// no source has a file left (the item is then a tombstone).
func Build(main string, sources []Source, metas map[string]media.Meta) (Data, bool) {
	files := map[string]*File{}
	var earliest = map[string]Member{} // per fingerprint, the oldest location (for the mtime fallback)
	for _, s := range sources {
		for _, m := range s.Members {
			f := files[m.Fingerprint]
			if f == nil {
				f = &File{Fingerprint: m.Fingerprint, Role: m.Role, Size: m.Size, Format: metas[m.Fingerprint].Format}
				files[m.Fingerprint] = f
			}
			if roleOrder(m.Role) < roleOrder(f.Role) {
				f.Role = m.Role
			}
			loc := Location{Source: s.Type}
			if s.Type == SourceNAS {
				loc.FolderID, loc.FolderName, loc.Path, loc.ModifiedAt = s.FolderID, s.FolderName, m.Path, m.MTime/1e6
				if f.Name == "" || m.Name < f.Name {
					f.Name = m.Name
				}
			} else {
				loc.SHA256 = m.SHA256
			}
			f.Locations = append(f.Locations, loc)
			if e, ok := earliest[m.Fingerprint]; !ok || m.MTime < e.MTime {
				earliest[m.Fingerprint] = m
			}
		}
	}
	if len(files) == 0 {
		return Data{}, false
	}
	d := Data{MainFingerprint: main}
	for _, f := range files {
		sort.Slice(f.Locations, func(i, j int) bool {
			a, b := f.Locations[i], f.Locations[j]
			if a.Source != b.Source {
				return a.Source > b.Source // nas before backup
			}
			if a.FolderID != b.FolderID {
				return a.FolderID < b.FolderID
			}
			if a.Path != b.Path {
				return a.Path < b.Path
			}
			return a.SHA256 < b.SHA256
		})
		d.Files = append(d.Files, *f)
	}
	sort.Slice(d.Files, func(i, j int) bool {
		a, b := d.Files[i], d.Files[j]
		if (a.Fingerprint == main) != (b.Fingerprint == main) {
			return a.Fingerprint == main
		}
		if roleOrder(a.Role) != roleOrder(b.Role) {
			return roleOrder(a.Role) < roleOrder(b.Role)
		}
		if a.Name != b.Name {
			return a.Name < b.Name
		}
		return a.Fingerprint < b.Fingerprint
	})

	var raw, rendered, video *File
	for i := range d.Files {
		f := &d.Files[i]
		switch {
		case f.Role == media.RoleRaw && raw == nil:
			raw = f
		case f.Role == media.RoleRendered && rendered == nil:
			rendered = f
		case f.Role == media.RoleVideo && video == nil:
			video = f
		}
	}
	mainMeta := metas[main]
	var embedded *media.Motion
	var stillFp string
	for _, f := range []*File{rendered, raw} {
		if f != nil && metas[f.Fingerprint].Motion != nil {
			embedded, stillFp = metas[f.Fingerprint].Motion, f.Fingerprint
			break
		}
	}
	switch {
	case raw == nil && rendered == nil:
		d.Kind = KindVideo
	case video != nil:
		d.Kind = KindMotion
		d.Motion = &Motion{Source: "file", Fingerprint: video.Fingerprint}
		d.Video = metas[video.Fingerprint].Video
	case embedded != nil:
		d.Kind = KindMotion
		d.Motion = &Motion{Source: "embedded", Fingerprint: stillFp, Format: embedded.Format, Offset: embedded.Offset,
			Length: embedded.Length, PresentationUs: embedded.PresentationUs}
		d.Video = embedded.Video
	case raw != nil && rendered != nil:
		d.Kind = KindPair
	default:
		d.Kind = KindPhoto
	}

	// Metadata: the main file's, each missing field filled from the other files in order (a RAW without a size
	// takes its rendering's; a motion photo's still gives the capture time before its video).
	order := []media.Meta{mainMeta}
	for _, f := range d.Files {
		if f.Fingerprint != main && f.Role != media.RoleSidecar {
			order = append(order, metas[f.Fingerprint])
		}
	}
	for _, m := range order {
		if d.Capture == nil && m.Capture != nil {
			c := *m.Capture
			d.Capture = &c
		}
		fill(&d.Make, m.Make)
		fill(&d.Model, m.Model)
		fill(&d.LensMake, m.LensMake)
		fill(&d.Lens, m.Lens)
		if d.GPS == nil && m.GPS != nil {
			g := *m.GPS
			d.GPS = &g
		}
		if d.Width == 0 && m.Width > 0 && m.Media == media.MediaImage || d.Kind == KindVideo && d.Width == 0 && m.Width > 0 {
			d.Width, d.Height = m.Width, m.Height
		}
		if d.Orientation == 0 && m.Media == media.MediaImage {
			d.Orientation = m.Orientation
		}
	}
	if d.Kind == KindVideo {
		d.Video = mainMeta.Video
	}
	if d.Capture == nil {
		if e, ok := earliest[main]; ok && e.MTime > 0 {
			d.Capture = media.MTimeCapture(e.MTime)
			if e.SHA256 != "" && e.Path == "" {
				d.Capture.Source = "upload"
			}
		}
	}
	d.SortTime = d.Capture.SortTime()
	return d, true
}

func fill(dst *string, v string) {
	if *dst == "" {
		*dst = v
	}
}
