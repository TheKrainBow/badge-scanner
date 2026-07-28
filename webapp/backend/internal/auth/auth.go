// Package auth implements the two independent protection layers requested:
// a static API client-id/secret gate on every request, and real user
// accounts (bcrypt password, JWT session cookie) with an env-bootstrapped
// admin and admin-managed user creation from then on.
package auth

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"

	"badgescanner/backend/internal/store"
)

const SessionCookieName = "badgescanner_session"
const sessionTTL = 24 * time.Hour

type Service struct {
	st           *store.Store
	jwtSecret    []byte
	secureCookie bool
}

// secureCookie should be true in any real deployment (HTTPS); set it false
// only for plain-HTTP local development, where browsers won't store a
// Secure cookie at all.
func NewService(st *store.Store, jwtSecret string, secureCookie bool) *Service {
	return &Service{st: st, jwtSecret: []byte(jwtSecret), secureCookie: secureCookie}
}

// Bootstrap creates the single hardcoded admin from env vars if (and only
// if) no users exist yet. Every user after that is created from the admin
// interface — see internal/api's admin user-management handlers.
func (s *Service) Bootstrap(adminUsername, adminPassword string) error {
	n, err := s.st.CountUsers()
	if err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	if adminUsername == "" || adminPassword == "" {
		return errors.New("no users exist yet: ADMIN_USERNAME and ADMIN_PASSWORD must be set to bootstrap the first admin")
	}
	hash, err := HashPassword(adminPassword)
	if err != nil {
		return err
	}
	_, err = s.st.CreateUser(adminUsername, hash, true)
	return err
}

func HashPassword(password string) (string, error) {
	b, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	return string(b), err
}

func CheckPassword(hash, password string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) == nil
}

type claims struct {
	UserID   int64  `json:"uid"`
	Username string `json:"username"`
	IsAdmin  bool   `json:"isAdmin"`
	jwt.RegisteredClaims
}

func (s *Service) IssueSession(w http.ResponseWriter, u store.User) error {
	now := time.Now()
	tok := jwt.NewWithClaims(jwt.SigningMethodHS256, claims{
		UserID:   u.ID,
		Username: u.Username,
		IsAdmin:  u.IsAdmin,
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(sessionTTL)),
		},
	})
	signed, err := tok.SignedString(s.jwtSecret)
	if err != nil {
		return err
	}
	http.SetCookie(w, &http.Cookie{
		Name:     SessionCookieName,
		Value:    signed,
		Path:     "/",
		HttpOnly: true,
		Secure:   s.secureCookie,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   int(sessionTTL.Seconds()),
	})
	return nil
}

func (s *Service) ClearSession(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name:     SessionCookieName,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		Secure:   s.secureCookie,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   -1,
	})
}

// Identity is the authenticated caller, attached to the request context.
type Identity struct {
	UserID   int64
	Username string
	IsAdmin  bool
}

type contextKey string

const identityContextKey contextKey = "identity"

func (s *Service) parseSession(r *http.Request) (*Identity, error) {
	cookie, err := r.Cookie(SessionCookieName)
	if err != nil {
		return nil, errors.New("not authenticated")
	}
	tok, err := jwt.ParseWithClaims(cookie.Value, &claims{}, func(t *jwt.Token) (interface{}, error) {
		return s.jwtSecret, nil
	})
	if err != nil || !tok.Valid {
		return nil, errors.New("invalid session")
	}
	c, ok := tok.Claims.(*claims)
	if !ok {
		return nil, errors.New("invalid session")
	}
	return &Identity{UserID: c.UserID, Username: c.Username, IsAdmin: c.IsAdmin}, nil
}

// RequireSession is HTTP middleware enforcing a valid user session.
func (s *Service) RequireSession(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id, err := s.parseSession(r)
		if err != nil {
			http.Error(w, `{"error":"authentication required"}`, http.StatusUnauthorized)
			return
		}
		ctx := context.WithValue(r.Context(), identityContextKey, id)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// RequireAdmin is HTTP middleware enforcing a valid *admin* session.
func (s *Service) RequireAdmin(next http.Handler) http.Handler {
	return s.RequireSession(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := FromContext(r.Context())
		if id == nil || !id.IsAdmin {
			http.Error(w, `{"error":"admin required"}`, http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	}))
}

func FromContext(ctx context.Context) *Identity {
	id, _ := ctx.Value(identityContextKey).(*Identity)
	return id
}

// RequireClientCredentials is HTTP middleware for the app-level API
// client-id/secret gate, checked before any user session — protects the
// backend from being hit by anything other than the known frontend/agent.
// This is a coarse app-level gate, not a strong secret (a browser build's
// secret is readable via devtools); the real security boundary is user
// auth plus network placement (VPN/reverse proxy/allowlist).
func RequireClientCredentials(clientID, clientSecret string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Client-Id") != clientID || r.Header.Get("X-Client-Secret") != clientSecret {
			http.Error(w, `{"error":"invalid client credentials"}`, http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}
