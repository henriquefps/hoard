package items

import (
	"sort"

	"github.com/henriquefps/hoard/server/internal/media"
)

// Source is one place an item's files were found: a NAS group, an uploaded original or a phone backup's group.
type Source struct {
	Type       string // nas | backup | trash | mirror
	FolderID   string
	FolderName string
	Members    []Member
	Names      map[string]string // mirror: NAS folder names (for the nas location of a NAS reference)
}

// Location is where one copy of a file lives.
type Location struct {
	Source     string `json:"source"` // nas | backup | mirror (ADR 0024)
	FolderID   string `json:"folderId,omitempty"`
	FolderName string `json:"folderName,omitempty"`
	// mirror (ADR 0024): the phone it is (or was) on and its root; Path is then the path on the phone's volume.
	PhysicalDeviceID string `json:"physicalDeviceId,omitempty"`
	DeviceName       string `json:"deviceName,omitempty"`
	RootID           string `json:"rootId,omitempty"`
	Volume           string `json:"volume,omitempty"`
	Path             string `json:"path,omitempty"`  // nas: relative to the folder, '/'-separated (in a trash: the original path)
	Trash            string `json:"trash,omitempty"` // nas, in a trash (ADR 0023): where the file is now, relative to the folder
	// Held (ADR 0025, re-review N2): an unfinished compression journal holds this trashed file (an original whose
	// replace waits, or what a restore moved). Hoard's Empty and auto-empty leave it, and so does the server.
	Held       bool   `json:"held,omitempty"`
	ModifiedAt int64  `json:"modifiedAt,omitempty"` // nas: the file's mtime, Unix ms; mirror: the phone's
	OnDevice   *bool  `json:"onDevice,omitempty"`   // mirror: false once the phone no longer has the file
	Gone       string `json:"gone,omitempty"`       // mirror, off the phone: freed | deleted | compressed (ADR 0025)
	SHA256     string `json:"sha256,omitempty"`     // backup: the blob
}

func sameLocation(a, b Location) bool {
	return a.Source == b.Source && a.FolderID == b.FolderID && a.Path == b.Path && a.Trash == b.Trash && a.SHA256 == b.SHA256 &&
		a.RootID == b.RootID && a.Volume == b.Volume && a.PhysicalDeviceID == b.PhysicalDeviceID
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
	// Video compression (ADR 0025). Compressed: on an output item, its record. CompressedTo and ReplacedBy: on a
	// trashed item whose file a compression replaced (or, after a restore, the trashed output), the live item that took
	// it over (the heir rule): edits, culling and clients follow it as for a tombstone's replacedBy.
	Compressed   *Compressed `json:"compressed,omitempty"`
	CompressedTo string      `json:"compressedTo,omitempty"`
	ReplacedBy   string      `json:"replacedBy,omitempty"`
	// ServerTrash (ADR 0023 Amendment (0.14.0)): the web gallery's admin moved the item's server copies (phone backups,
	// uploads) into the server trash; its blobs stay until purgeAfter (auto-empty) or Delete permanently.
	ServerTrash *ServerTrash `json:"serverTrash,omitempty"`
}

// ServerTrash is the "serverTrash" object of an item in the server trash.
type ServerTrash struct {
	Since      int64 `json:"since"`      // ms
	PurgeAfter int64 `json:"purgeAfter"` // ms
}

// Compressed is the "compressed" object of an output item (ADR 0025 "What the walk and the items do").
type Compressed struct {
	From         string `json:"from"`     // the original's fingerprint
	FromItem     string `json:"fromItem"` // the original's item
	Profile      string `json:"profile"`
	Q            int    `json:"q"`
	At           int64  `json:"at"`       // when the output replaced the original (ms)
	Original     string `json:"original"` // trashed | purged
	OriginalSize int64  `json:"originalSize"`
	MetaSidecar  bool   `json:"metaSidecar"`
}

// Build computes an item from its sources and the stored metadata of its files (ADR 0014 "Items"). ok is false when
// no source has a file left (the item is then a tombstone). Files in a trash are locations like any other (with their
// trash path, ADR 0023): the API leaves them out for clients that did not ask for the trash.
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
			var extra []Location
			switch {
			case s.Type == SourceNAS || s.Type == SourceTrash:
				loc.Source = SourceNAS
				loc.FolderID, loc.FolderName, loc.Path, loc.ModifiedAt, loc.Trash = s.FolderID, s.FolderName, m.Path, m.MTime/1e6, m.Trash
				if f.Name == "" || m.Name < f.Name {
					f.Name = m.Name
				}
			case (s.Type == SourceMirror || s.Type == SourceKept) && m.Mirror != nil:
				loc.Source = SourceMirror
				// The phone's location, next to the backup or nas location that holds the bytes (ADR 0024).
				mr := m.Mirror
				on := mr.OnDevice
				loc.PhysicalDeviceID, loc.DeviceName, loc.RootID, loc.Volume = mr.PhysicalID, mr.DeviceName, mr.RootID, mr.Volume
				loc.Path, loc.ModifiedAt, loc.OnDevice, loc.Gone = m.Path, m.MTime/1e6, &on, mr.Gone
				if f.Name == "" || m.Name < f.Name {
					f.Name = m.Name
				}
				if m.SHA256 != "" {
					extra = append(extra, Location{Source: SourceBackup, SHA256: m.SHA256})
				} else if mr.NasPath != "" {
					extra = append(extra, Location{Source: SourceNAS, FolderID: mr.NasFolderID, FolderName: s.Names[mr.NasFolderID],
						Path: mr.NasPath, ModifiedAt: mr.NasMTime / 1e6})
				}
			default:
				loc.SHA256 = m.SHA256
			}
			for _, l := range append([]Location{loc}, extra...) {
				dup := false
				for _, have := range f.Locations {
					dup = dup || sameLocation(have, l)
				}
				if !dup {
					f.Locations = append(f.Locations, l)
				}
			}
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
				return a.Source > b.Source // nas, then mirror, then backup
			}
			if a.FolderID != b.FolderID {
				return a.FolderID < b.FolderID
			}
			if a.RootID != b.RootID {
				return a.RootID < b.RootID
			}
			if a.Volume != b.Volume {
				return a.Volume < b.Volume
			}
			if a.Path != b.Path {
				return a.Path < b.Path
			}
			if a.Trash != b.Trash {
				return a.Trash < b.Trash // the live copy first
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

// Live returns the item without its locations in a trash (ADR 0023) and without the member files left with none: what
// a client that does not ask for the trash, and the web gallery, see.
func (d Data) Live() Data {
	files := make([]File, 0, len(d.Files))
	for _, f := range d.Files {
		locs := make([]Location, 0, len(f.Locations))
		for _, l := range f.Locations {
			if l.Trash == "" {
				locs = append(locs, l)
			}
		}
		if len(locs) == 0 {
			continue
		}
		f.Locations = locs
		files = append(files, f)
	}
	d.Files = files
	return d
}
