package service

import (
	"time"

	"badgescanner/backend/internal/store"
)

// RefreshCADirectory is the server-side port of CaDirectory.refresh: a full
// (slow) refetch of the CA user listing, kept only for IsListable entries,
// and — same rule as the Kotlin doc comment — clears every manual badge
// link, since a fresh directory supersedes hand-made links.
func (s *Service) RefreshCADirectory() (int, error) {
	settings, err := s.Store.GetSettings()
	if err != nil {
		return 0, err
	}
	fresh, err := s.CA.FetchAllUsers(s.caConfig(settings), 30, nil)
	if err != nil {
		return 0, err
	}

	var filtered []store.CADirEntry
	for _, e := range fresh {
		if e.IsListable() {
			filtered = append(filtered, e)
		}
	}

	if err := s.Store.ReplaceCADirectory(filtered, time.Now().UnixMilli()); err != nil {
		return 0, err
	}
	if err := s.Store.ClearManualLinks(); err != nil {
		return 0, err
	}
	return len(filtered), nil
}

type CADirectoryInfo struct {
	UserCount int   `json:"userCount"`
	FetchedAt int64 `json:"fetchedAt"`
}

func (s *Service) CADirectoryInfo() (CADirectoryInfo, error) {
	entries, err := s.Store.ListCADirectory()
	if err != nil {
		return CADirectoryInfo{}, err
	}
	fetchedAt, err := s.Store.CADirectoryFetchedAt()
	if err != nil {
		return CADirectoryInfo{}, err
	}
	return CADirectoryInfo{UserCount: len(entries), FetchedAt: fetchedAt}, nil
}
