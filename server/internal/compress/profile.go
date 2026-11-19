// Package compress is hoard-server's video compression (ADR 0025): the probe and the skip rules, the GPU queue, the
// NVENC encoder, the verification and the replace of NAS files through a write mount, with the original moved into
// the trash (ADR 0023) and back on restore.
package compress

import (
	"encoding/json"
	"fmt"
)

// Profile is a compression profile (ADR 0025 "Profiles").
type Profile struct {
	ID      string `json:"id"`
	Codec   string `json:"codec"` // hevc | av1
	Q       int    `json:"q"`
	Default bool   `json:"default,omitempty"`
	OptIn   bool   `json:"optIn,omitempty"`
}

// Profiles in the order the server lists them.
var Profiles = []Profile{
	{ID: "hevc-cq28", Codec: "hevc", Q: 28, Default: true},
	{ID: "hevc-cq30", Codec: "hevc", Q: 30},
	{ID: "av1-cq34", Codec: "av1", Q: 34, OptIn: true},
}

// DefaultProfile is the profile of automatic folders and of jobs that name none.
const DefaultProfile = "hevc-cq28"

// ProfileByID returns a profile.
func ProfileByID(id string) (Profile, bool) {
	for _, p := range Profiles {
		if p.ID == id {
			return p, true
		}
	}
	return Profile{}, false
}

// Quality modes (ADR 0025 "Quality mode C").
const (
	ModeFloor  = "floor"  // re-encode at q-2, q-4 while VMAF is under the floor (93)
	ModeFixed  = "fixed"  // one encode
	ModeTarget = "target" // the same search towards a higher score (95)
)

// Option sets.
const (
	SetLean    = "lean"
	SetQuality = "quality" // the owner's draft without AQ: -multipass fullres -rc-lookahead 32 -bf 4 -b_ref_mode middle
)

// Options are a job's options.
type Options struct {
	Quality struct {
		Mode string  `json:"mode"`
		VMAF float64 `json:"vmaf,omitempty"`
	} `json:"quality"`
	Set string `json:"set"`
}

// DefaultFloor is mode C's floor; a final score under DefaultFloor-1 fails the item.
const DefaultFloor = 93.0

// ParseOptions validates options (nil or {} = the defaults) and fills the defaults.
func ParseOptions(raw json.RawMessage) (Options, error) {
	var o Options
	if len(raw) > 0 && string(raw) != "null" {
		if err := json.Unmarshal(raw, &o); err != nil {
			return o, fmt.Errorf("options: %w", err)
		}
	}
	switch o.Quality.Mode {
	case "":
		o.Quality.Mode = ModeFloor
	case ModeFloor, ModeFixed, ModeTarget:
	default:
		return o, fmt.Errorf("options.quality.mode is floor, fixed or target")
	}
	if o.Quality.VMAF == 0 {
		o.Quality.VMAF = DefaultFloor
		if o.Quality.Mode == ModeTarget {
			o.Quality.VMAF = 95
		}
	}
	if o.Quality.VMAF < 80 || o.Quality.VMAF > 99 {
		return o, fmt.Errorf("options.quality.vmaf is 80 to 99")
	}
	switch o.Set {
	case "":
		o.Set = SetLean
	case SetLean, SetQuality:
	default:
		return o, fmt.Errorf("options.set is lean or quality")
	}
	return o, nil
}

// Marker is the metadata key of every output's marker. exiftool writes it into the output's QuickTime Keys, where it
// gets Apple's namespace (MarkerKey, ADR 0025 Amendment (N4)); a probe accepts both.
const Marker = "dev.hfps.hoard.compressed"

// MarkerKey is the Keys entry the marker is written as: exiftool prefixes every key outside the com., samsung. and
// xiaomi. domains with com.apple.quicktime.
const MarkerKey = "com.apple.quicktime." + Marker

// markerValue is the marker of an encode: v1, <codec>-cq<q>, <yyyy-MM-dd>.
func markerValue(r EncodeRequest) string {
	return fmt.Sprintf("v1, %s-cq%d, %s", r.Profile.Codec, r.Q, r.Date)
}

// exiftoolConfig names the marker for exiftool (a user-defined Keys tag, HoardCompressed).
const exiftoolConfig = `%Image::ExifTool::UserDefined = (
    'Image::ExifTool::QuickTime::Keys' => {
        '` + Marker + `' => { Name => 'HoardCompressed' },
    },
);
1;
`
