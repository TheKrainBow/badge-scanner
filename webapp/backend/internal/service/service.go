// Package service is the server-side port of
// mobile/app/.../scan/ScanViewModel.kt: the scan flow, CA directory refresh,
// user aggregation, coalition/TIG actions and cluster caching, now backed
// by the shared SQLite store instead of per-device state.
package service

import (
	"fmt"
	"strconv"
	"strings"
	"time"

	"badgescanner/backend/internal/caclient"
	"badgescanner/backend/internal/intraclient"
	"badgescanner/backend/internal/logins"
	"badgescanner/backend/internal/store"
	"badgescanner/backend/internal/wiegand"
)

type Service struct {
	Store *store.Store
	CA    *caclient.Client
	Intra *intraclient.Client
}

func New(st *store.Store, ca *caclient.Client, intra *intraclient.Client) *Service {
	return &Service{Store: st, CA: ca, Intra: intra}
}

func (s *Service) caConfig(a store.AppSettings) caclient.Config {
	return caclient.Config{Endpoint: a.CAEndpoint, Username: a.CAUsername, Password: a.CAPassword}
}

func (s *Service) intraConfig(a store.AppSettings) intraclient.Config {
	return intraclient.Config{TokenURL: a.FTTokenURL, Endpoint: a.FTEndpoint, UID: a.FTUid, Secret: a.FTSecret}
}

// ScanOutcome tells the frontend how to react — mirrors the mobile app's
// ScanState: a matched CA user opens the user page directly (no result
// card), an unmatched-but-resolved login shows the plain result card, and
// anything else is a failure the operator has to handle (e.g. Associate).
type ScanOutcome struct {
	Status string            `json:"status"` // "user" | "success" | "failure"
	Record store.ScanRecord  `json:"record"`
	Entry  *store.CADirEntry `json:"entry,omitempty"`
}

// Scan is the server-side port of ScanViewModel.process(): Wiegand → CA
// directory cache lookup → manual link fallback → intra cache/live
// resolve → history write.
func (s *Service) Scan(uidHex string) (ScanOutcome, error) {
	codes, err := wiegand.FromUIDHex(uidHex)
	if err != nil {
		return ScanOutcome{}, err
	}

	settings, err := s.Store.GetSettings()
	if err != nil {
		return ScanOutcome{}, err
	}

	var login, ftID, photoURL, matchedBadgeID, userType, coalitionName, coalitionColor, coalitionImageURL string
	var matchedEntry *store.CADirEntry
	var scanErr string

	if !settings.CAConfigured() {
		scanErr = "CA credentials are not configured (see Admin settings)"
	} else {
		entry, badgeNum, found, err := s.Store.FindByBadge(codes.CACandidates())
		if err != nil {
			return ScanOutcome{}, err
		}
		if !found {
			manualLogin, hasManual, err := s.Store.GetManualLink(codes.UIDHex)
			if err != nil {
				return ScanOutcome{}, err
			}
			if hasManual {
				login = manualLogin
				entries, _ := s.Store.ListCADirectory()
				for _, e := range entries {
					if strings.EqualFold(e.DisplayLogin(), manualLogin) {
						ec := e
						matchedEntry = &ec
						break
					}
				}
			} else {
				dirEntries, _ := s.Store.ListCADirectory()
				scanErr = fmt.Sprintf(
					"Badge not in CA directory (tried %s; %d users cached). "+
						"If this badge is new, use “Refetch CA users” in Admin, or associate it to a student.",
					strings.Join(codes.CACandidates(), ", "), len(dirEntries))
			}
		} else {
			matchedBadgeID = strconv.FormatInt(badgeNum, 10)
			ec := entry
			matchedEntry = &ec
			login = entry.FTLogin
			ftID = entry.FTId
			if login == "" && ftID == "" {
				login = logins.PiscineLoginFromName(entry.FullName)
			}
			if login == "" && ftID == "" {
				name := entry.FullName
				if name == "" {
					name = strconv.FormatInt(entry.PK, 10)
				}
				scanErr = fmt.Sprintf("CA user %s has no ft_login and no ft_id", name)
			}
		}
	}

	if scanErr == "" {
		if !settings.FTConfigured() {
			if login == "" {
				scanErr = "42 API credentials are not configured (see Admin settings)"
			}
		} else if matchedEntry != nil {
			cacheKey := ftID
			if cacheKey == "" {
				cacheKey = login
			}
			if cacheKey != "" {
				if cached, ok, err := s.Store.PeekIntra(cacheKey); err == nil && ok {
					if cached.Login != nil {
						login = *cached.Login
					}
					if cached.FTId != nil {
						ftID = *cached.FTId
					}
					photoURL = derefOr(cached.PhotoURL, "")
					userType = derefOr(cached.UserType, "")
					coalitionName = derefOr(cached.CoalitionName, "")
					coalitionColor = derefOr(cached.CoalitionColor, "")
					coalitionImageURL = derefOr(cached.CoalitionImageURL, "")
				}
			}
		} else {
			info, err := s.resolveIntra(settings, login, ftID)
			if err != nil {
				if login == "" {
					scanErr = err.Error()
				}
			} else if info != nil {
				if info.Login != nil {
					login = *info.Login
				}
				if info.FTId != nil {
					ftID = *info.FTId
				}
				photoURL = derefOr(info.PhotoURL, "")
				userType = derefOr(info.UserType, "")
				coalitionName = derefOr(info.CoalitionName, "")
				coalitionColor = derefOr(info.CoalitionColor, "")
				coalitionImageURL = derefOr(info.CoalitionImageURL, "")
			}
		}
	}

	wiegandVal := codes.Wiegand26
	if matchedBadgeID != "" {
		wiegandVal = matchedBadgeID
	}
	record := store.ScanRecord{
		Timestamp: time.Now().UnixMilli(),
		UIDHex:    codes.UIDHex,
		MifareHex: codes.MifareHex,
		Wiegand:   wiegandVal,
		Login:     strPtrOrNil(login),
		FTId:      strPtrOrNil(ftID),
		PhotoURL:  strPtrOrNil(photoURL),
		Error:     strPtrOrNil(scanErr),
		UserType:  strPtrOrNil(userType),

		CoalitionName:     strPtrOrNil(coalitionName),
		CoalitionColor:    strPtrOrNil(coalitionColor),
		CoalitionImageURL: strPtrOrNil(coalitionImageURL),
	}
	saved, err := s.Store.AddScanRecord(record)
	if err != nil {
		return ScanOutcome{}, err
	}

	switch {
	case scanErr == "" && login != "" && matchedEntry != nil:
		return ScanOutcome{Status: "user", Record: saved, Entry: matchedEntry}, nil
	case scanErr == "" && login != "":
		return ScanOutcome{Status: "success", Record: saved}, nil
	default:
		return ScanOutcome{Status: "failure", Record: saved}, nil
	}
}

// resolveIntra mirrors ScanViewModel.resolveIntra: 12h-cache-or-fetch.
func (s *Service) resolveIntra(settings store.AppSettings, login, ftID string) (*store.IntraInfo, error) {
	cacheKey := ftID
	if cacheKey == "" {
		cacheKey = login
	}
	if cacheKey == "" {
		return nil, nil
	}
	if fresh, ok, err := s.Store.GetFreshIntra(cacheKey); err == nil && ok {
		return &fresh, nil
	}
	info, err := s.fetchIntraInfo(settings, login, ftID)
	if err != nil {
		return nil, err
	}
	if err := s.Store.PutIntra(cacheKey, info); err != nil {
		return nil, err
	}
	return &info, nil
}

// fetchIntraInfo mirrors ScanViewModel.fetchIntraInfo: profile + best-effort coalition.
func (s *Service) fetchIntraInfo(settings store.AppSettings, login, ftID string) (store.IntraInfo, error) {
	cfg := s.intraConfig(settings)
	user, err := s.Intra.FetchUser(cfg, ftID, login)
	if err != nil {
		return store.IntraInfo{}, err
	}
	userType := userTypeFromCursus(user.CursusIDs)
	resolvedLogin := user.Login
	if resolvedLogin == "" {
		resolvedLogin = login
	}
	resolvedFTId := user.ID
	if resolvedFTId == "" {
		resolvedFTId = ftID
	}
	info := store.IntraInfo{
		FetchedAt:       time.Now().UnixMilli(),
		Login:           strPtrOrNil(resolvedLogin),
		FTId:            strPtrOrNil(resolvedFTId),
		PhotoURL:        strPtrOrNil(user.ImageURL),
		UserType:        strPtrOrNil(userType),
		Location:        strPtrOrNil(user.Location),
		Level:           user.Level,
		CurrentProjects: user.CurrentProjects,
	}
	if resolvedLogin != "" {
		coalitions, err := s.Intra.FetchCoalitions(cfg, resolvedLogin)
		if err == nil {
			if picked := pickCoalition(coalitions); picked != nil {
				info.CoalitionName = strPtrOrNil(picked.Name)
				info.CoalitionColor = strPtrOrNil(picked.Color)
				info.CoalitionImageURL = strPtrOrNil(picked.ImageURL)
				id := picked.ID
				info.CoalitionID = &id
				if cus, err := s.Intra.FetchCoalitionsUsers(cfg, resolvedLogin); err == nil {
					for _, cu := range cus {
						if cu.CoalitionID == picked.ID {
							cuid := cu.ID
							info.CoalitionsUserID = &cuid
							break
						}
					}
				}
			}
		}
	}
	return info, nil
}

// Priority order for theming/display: main coalitions win over piscine
// ones; otherwise the highest-scoring coalition — ports
// ScanViewModel.pickCoalition.
var priorityCoalitions = []string{"harkonnen", "corrino", "atreides"}
var secondaryCoalitions = []string{"hordes", "alliance"}

func pickCoalition(coalitions []intraclient.Coalition) *intraclient.Coalition {
	match := func(keys []string) *intraclient.Coalition {
		for _, key := range keys {
			for i := range coalitions {
				c := coalitions[i]
				if strings.EqualFold(c.Slug, key) || strings.EqualFold(c.Name, key) {
					return &c
				}
			}
		}
		return nil
	}
	if c := match(priorityCoalitions); c != nil {
		return c
	}
	if c := match(secondaryCoalitions); c != nil {
		return c
	}
	if len(coalitions) == 0 {
		return nil
	}
	best := coalitions[0]
	for _, c := range coalitions[1:] {
		if c.Score > best.Score {
			best = c
		}
	}
	return &best
}

// userTypeFromCursus ports ScanViewModel.userTypeFromCursus: cursus 21 =>
// main student cursus, cursus 9 => piscine.
func userTypeFromCursus(cursusIDs []int) string {
	for _, id := range cursusIDs {
		if id == 21 {
			return "Student"
		}
	}
	for _, id := range cursusIDs {
		if id == 9 {
			return "Piscine"
		}
	}
	return ""
}

func strPtrOrNil(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func derefOr(p *string, fallback string) string {
	if p == nil {
		return fallback
	}
	return *p
}

func nowMillis() int64 {
	return time.Now().UnixMilli()
}
