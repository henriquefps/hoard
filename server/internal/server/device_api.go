package server

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"sort"
	"strings"

	"github.com/henriquefps/hoard/server/internal/store"
)

// Physical devices (ADR 0017, hoard-server 0.7.0): additions under /v1 (routed in routesAPI).

var appPattern = regexp.MustCompile(`^[a-z0-9-]{1,32}$`)

type deviceEntry struct {
	ID           string  `json:"id"`
	Name         string  `json:"name"`
	App          *string `json:"app"`
	Platform     string  `json:"platform"`
	Manufacturer *string `json:"manufacturer"`
	Model        *string `json:"model"`
}

type physicalView struct {
	ID      string        `json:"id"`
	Name    string        `json:"name"`
	Devices []deviceEntry `json:"devices"`
}

type candidate struct {
	PhysicalDeviceID string        `json:"physicalDeviceId"`
	Name             string        `json:"name"`
	Match            string        `json:"match"` // model | other
	Devices          []deviceEntry `json:"devices"`
	lastSeen         int64
}

type deviceView struct {
	DeviceID       string       `json:"deviceId"`
	Name           string       `json:"name"`
	Platform       string       `json:"platform"`
	App            *string      `json:"app"`
	Manufacturer   *string      `json:"manufacturer"`
	Model          *string      `json:"model"`
	PhysicalDevice physicalView `json:"physicalDevice"`
	Candidates     []candidate  `json:"candidates"`
}

func entry(d store.Device) deviceEntry {
	return deviceEntry{ID: d.ID, Name: d.Name, App: d.App, Platform: d.Platform, Manufacturer: d.Manufacturer, Model: d.Model}
}

func same(a, b *string) bool {
	return a != nil && b != nil && *a != "" && strings.EqualFold(strings.TrimSpace(*a), strings.TrimSpace(*b))
}

// groups returns the live devices of every physical device, oldest first.
func groups(all []store.Device) map[string][]store.Device {
	out := map[string][]store.Device{}
	for i := len(all) - 1; i >= 0; i-- { // Devices lists newest first
		d := all[i]
		if d.RevokedAt == nil {
			out[d.PhysicalID] = append(out[d.PhysicalID], d)
		}
	}
	return out
}

// candidatesFor lists the physical devices d may belong to (ADR 0017): other groups on the same platform with a live
// device and no device of d's app; same manufacturer and model first.
func candidatesFor(d store.Device, all []store.Device) []candidate {
	out := []candidate{}
	for id, members := range groups(all) {
		if id == d.PhysicalID || len(members) == 0 {
			continue
		}
		c := candidate{PhysicalDeviceID: id, Name: members[0].Name, Match: "other", Devices: []deviceEntry{}}
		ok := false
		for _, m := range members {
			if m.Platform != d.Platform {
				continue
			}
			ok = true
			if d.App != nil && m.App != nil && *m.App == *d.App {
				ok = false
				break
			}
			if same(m.Manufacturer, d.Manufacturer) && same(m.Model, d.Model) {
				c.Match = "model"
			}
			if m.LastSeenAt != nil && *m.LastSeenAt > c.lastSeen {
				c.lastSeen = *m.LastSeenAt
			}
			c.Devices = append(c.Devices, entry(m))
		}
		if ok {
			out = append(out, c)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if (a.Match == "model") != (b.Match == "model") {
			return a.Match == "model"
		}
		if a.lastSeen != b.lastSeen {
			return a.lastSeen > b.lastSeen
		}
		return a.PhysicalDeviceID < b.PhysicalDeviceID
	})
	return out
}

func (s *Server) deviceView(ctx context.Context, id string) (deviceView, error) {
	d, err := s.store.DeviceByID(ctx, id)
	if err != nil {
		return deviceView{}, err
	}
	all, err := s.store.Devices(ctx)
	if err != nil {
		return deviceView{}, err
	}
	v := deviceView{DeviceID: d.ID, Name: d.Name, Platform: d.Platform, App: d.App, Manufacturer: d.Manufacturer, Model: d.Model,
		PhysicalDevice: physicalView{ID: d.PhysicalID, Name: d.Name, Devices: []deviceEntry{}}, Candidates: candidatesFor(d, all)}
	members := groups(all)[d.PhysicalID]
	if len(members) > 0 {
		v.PhysicalDevice.Name = members[0].Name
	}
	for _, m := range members {
		v.PhysicalDevice.Devices = append(v.PhysicalDevice.Devices, entry(m))
	}
	return v, nil
}

func (s *Server) writeDeviceView(w http.ResponseWriter, r *http.Request, id string) {
	v, err := s.deviceView(r.Context(), id)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, v)
}

func (s *Server) handleDevice(w http.ResponseWriter, r *http.Request) {
	s.writeDeviceView(w, r, info(r).device.ID)
}

// readDeviceInfo validates app, manufacturer and model (each optional).
func readDeviceInfo(app, manufacturer, model *string) (store.DeviceInfo, bool) {
	var out store.DeviceInfo
	if app != nil {
		if !appPattern.MatchString(*app) {
			return out, false
		}
		out.App = app
	}
	for _, p := range []struct {
		in  *string
		out **string
	}{{manufacturer, &out.Manufacturer}, {model, &out.Model}} {
		if p.in == nil {
			continue
		}
		v, ok := cleanText(*p.in, 64)
		if !ok {
			return out, false
		}
		*p.out = &v
	}
	return out, true
}

const deviceInfoProblem = "app is [a-z0-9-]{1,32}; manufacturer and model are at most 64 characters."

func (s *Server) handleDeviceUpdate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		App          *string `json:"app"`
		Manufacturer *string `json:"manufacturer"`
		Model        *string `json:"model"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	di, ok := readDeviceInfo(req.App, req.Manufacturer, req.Model)
	if !ok {
		fail(w, r, http.StatusBadRequest, "invalid_request", deviceInfoProblem, nil)
		return
	}
	if err := s.store.UpdateDeviceInfo(r.Context(), info(r).device.ID, di); err != nil {
		s.internal(w, r, err)
		return
	}
	s.writeDeviceView(w, r, info(r).device.ID)
}

// readPhysicalTarget reads {"physicalDeviceId": "<uuid>" | null}; "" means "a physical device of its own".
func readPhysicalTarget(w http.ResponseWriter, r *http.Request) (string, bool) {
	var req struct {
		PhysicalDeviceID json.RawMessage `json:"physicalDeviceId"`
	}
	if !readJSON(w, r, &req) {
		return "", false
	}
	if len(req.PhysicalDeviceID) == 0 || string(req.PhysicalDeviceID) == "null" {
		return "", true
	}
	var id string
	if json.Unmarshal(req.PhysicalDeviceID, &id) != nil || !uuidPattern.MatchString(id) {
		fail(w, r, http.StatusBadRequest, "invalid_request", "physicalDeviceId is a lower-case UUID or null.", nil)
		return "", false
	}
	return id, true
}

// moveDevice applies a claim or an admin move and reports errors; ok when the device moved (or stayed).
func (s *Server) moveDevice(w http.ResponseWriter, r *http.Request, deviceID, to string) bool {
	_, err := s.store.MoveDevice(r.Context(), deviceID, to)
	switch {
	case errors.Is(err, store.ErrNotFound):
		fail(w, r, http.StatusNotFound, "not_found", "No such physical device.", nil)
	case errors.Is(err, store.ErrSameApp):
		fail(w, r, http.StatusConflict, "conflict", "That physical device already has this app paired: revoke the old one first.",
			map[string]any{"reason": "same_app"})
	case err != nil:
		s.internal(w, r, err)
	default:
		s.log.Info("device grouped", "device", deviceID)
		return true
	}
	return false
}

func (s *Server) handleDeviceClaim(w http.ResponseWriter, r *http.Request) {
	to, ok := readPhysicalTarget(w, r)
	if !ok {
		return
	}
	if s.moveDevice(w, r, info(r).device.ID, to) {
		s.writeDeviceView(w, r, info(r).device.ID)
	}
}

// handleAdminMoveDevice is the admin page's "Move to…" (ADR 0017 "Admin page").
func (s *Server) handleAdminMoveDevice(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !uuidPattern.MatchString(id) {
		fail(w, r, http.StatusNotFound, "not_found", "No such device.", nil)
		return
	}
	if _, err := s.store.DeviceByID(r.Context(), id); errors.Is(err, store.ErrNotFound) {
		fail(w, r, http.StatusNotFound, "not_found", "No such device.", nil)
		return
	}
	to, ok := readPhysicalTarget(w, r)
	if !ok {
		return
	}
	if s.moveDevice(w, r, id, to) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	}
}
