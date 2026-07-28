// Package api wires the HTTP routes: the client-id/secret gate, user
// session auth, and handlers delegating to internal/service.
package api

import (
	"encoding/json"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"

	"badgescanner/backend/internal/auth"
	"badgescanner/backend/internal/service"
)

type API struct {
	svc  *service.Service
	auth *auth.Service
}

func NewRouter(svc *service.Service, authSvc *auth.Service, clientID, clientSecret string) http.Handler {
	a := &API{svc: svc, auth: authSvc}

	r := chi.NewRouter()
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)

	r.Get("/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})

	r.Route("/api", func(r chi.Router) {
		r.Use(func(next http.Handler) http.Handler {
			return auth.RequireClientCredentials(clientID, clientSecret, next)
		})

		r.Post("/auth/login", a.login)
		r.Post("/auth/logout", a.logout)

		r.Group(func(r chi.Router) {
			r.Use(authSvc.RequireSession)

			r.Get("/auth/me", a.me)

			r.Post("/scan", a.scan)
			r.Post("/badges/associate", a.associateBadge)

			r.Get("/history", a.listHistory)
			r.Patch("/history/{id}", a.patchHistory)
			r.Delete("/history/{id}", a.deleteHistory)
			r.Delete("/history", a.clearHistory)

			r.Get("/users", a.listUsers)
			r.Get("/users/{pk}", a.getUser)
			r.Delete("/users/{pk}", a.deleteUser)
			r.Post("/users/{pk}/refresh-profile", a.refreshUserProfile)
			r.Post("/users/{pk}/refresh-coalition", a.refreshUserCoalition)
			r.Post("/users/{pk}/manual-blame", a.addManualBlame)

			r.Post("/coalitions/score", a.coalitionScore)
			r.Post("/tig", a.giveTig)

			r.Get("/clusters", a.getClusters)
			r.Post("/clusters/refresh-occupants", a.refreshOccupants)

			r.Get("/ca/info", a.caInfo)

			r.Group(func(r chi.Router) {
				r.Use(authSvc.RequireAdmin)
				r.Post("/ca/refresh", a.refreshCADirectory)
				r.Get("/admin/settings", a.getSettings)
				r.Put("/admin/settings", a.putSettings)
				r.Get("/admin/users", a.listAccounts)
				r.Post("/admin/users", a.createAccount)
				r.Delete("/admin/users/{id}", a.deleteAccount)
				r.Patch("/admin/users/{id}", a.patchAccount)
			})
		})
	})

	return r
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func decodeJSON(r *http.Request, v any) error {
	return json.NewDecoder(r.Body).Decode(v)
}
