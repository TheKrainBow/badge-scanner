// Command reader-agent bridges a PC/SC NFC reader (e.g. the ACR122U) to the
// badge-scanner webapp's Scan page over a loopback WebSocket, since browsers
// cannot talk to PC/SC directly.
//
// Requires pcscd running and a PC/SC reader plugged in. Build needs cgo and
// libpcsclite-dev headers (`apt install libpcsclite-dev pcscd`).
package main

import (
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/ebfe/scard"
	"github.com/gorilla/websocket"
)

const (
	listenAddr   = "127.0.0.1:17420"
	debounce     = 2500 * time.Millisecond
	getUIDApdu   = "\xFF\xCA\x00\x00\x00"
	pollInterval = 500 * time.Millisecond
)

type hub struct {
	mu      sync.Mutex
	clients map[*websocket.Conn]bool
}

func newHub() *hub { return &hub{clients: map[*websocket.Conn]bool{}} }

func (h *hub) add(c *websocket.Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.clients[c] = true
}

func (h *hub) remove(c *websocket.Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.clients, c)
	c.Close()
}

func (h *hub) broadcast(v any) {
	body, err := json.Marshal(v)
	if err != nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.clients {
		if err := c.WriteMessage(websocket.TextMessage, body); err != nil {
			c.Close()
			delete(h.clients, c)
		}
	}
}

var upgrader = websocket.Upgrader{
	// Loopback-only server (see main's listener bind); any local page can
	// open this socket, same trust model as e.g. a local dev server.
	CheckOrigin: func(r *http.Request) bool { return true },
}

func main() {
	h := newHub()
	readerName := "none"

	go pcscLoop(h, &readerName)

	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "ok", "reader": readerName})
	})
	mux.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			log.Printf("ws upgrade: %v", err)
			return
		}
		h.add(conn)
		log.Printf("frontend connected (%s)", r.RemoteAddr)
		defer h.remove(conn)
		// Drain/ignore any client->agent messages; this socket is
		// agent->browser only, but we still need to notice a closed
		// connection.
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	})

	log.Printf("reader-agent listening on %s (loopback only)", listenAddr)
	log.Fatal(http.ListenAndServe(listenAddr, mux))
}

// pcscLoop waits for a card on any connected PC/SC reader, reads its UID via
// the standard "get UID" pseudo-APDU, and broadcasts it — debounced the same
// way the mobile app's onTag is (2.5s) so a badge left on the reader doesn't
// spam repeat events.
func pcscLoop(h *hub, readerName *string) {
	for {
		if err := runPCSC(h, readerName); err != nil {
			log.Printf("PC/SC error: %v — retrying in 5s (is pcscd running and a reader plugged in?)", err)
			*readerName = "none"
			time.Sleep(5 * time.Second)
		}
	}
}

func runPCSC(h *hub, readerName *string) error {
	ctx, err := scard.EstablishContext()
	if err != nil {
		return err
	}
	defer ctx.Release()

	var lastUID string
	var lastAt time.Time

	// states/trackedReaders persist across iterations: SCardGetStatusChange
	// only blocks for pollInterval when CurrentState reflects what it
	// itself last reported. Resetting CurrentState to StateUnaware every
	// call (an earlier version of this code did) makes every single call
	// look like an immediate change from "unaware", so it returns
	// instantly instead of blocking — a tight, CPU-pegging busy loop with
	// no actual polling delay. Only reset to StateUnaware the first time a
	// reader is seen; after that, feed EventState back in as the next
	// call's CurrentState.
	var states []scard.ReaderState
	var trackedReaders []string

	for {
		readers, err := ctx.ListReaders()
		if err != nil {
			return err
		}
		if len(readers) == 0 {
			*readerName = "none"
			trackedReaders = nil
			states = nil
			time.Sleep(pollInterval)
			continue
		}
		*readerName = readers[0]

		if !sameReaders(readers, trackedReaders) {
			trackedReaders = append([]string(nil), readers...)
			states = make([]scard.ReaderState, len(readers))
			for i, r := range readers {
				states[i].Reader = r
				states[i].CurrentState = scard.StateUnaware
			}
		}

		if err := ctx.GetStatusChange(states, pollInterval); err != nil && err != scard.ErrTimeout {
			return err
		}

		for i := range states {
			st := states[i]
			// Carry the reported state forward as the baseline for the
			// next call — this is what makes the next GetStatusChange
			// actually block instead of firing again immediately.
			states[i].CurrentState = st.EventState

			if st.EventState&scard.StatePresent == 0 {
				continue
			}
			uid, err := readUID(ctx, readers[i])
			if err != nil {
				log.Printf("read UID from %s: %v", readers[i], err)
				continue
			}
			now := time.Now()
			if uid == lastUID && now.Sub(lastAt) < debounce {
				continue
			}
			lastUID = uid
			lastAt = now
			log.Printf("badge tapped: %s", uid)
			h.broadcast(map[string]string{"uidHex": uid})
		}
	}
}

func sameReaders(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func readUID(ctx *scard.Context, reader string) (string, error) {
	card, err := ctx.Connect(reader, scard.ShareShared, scard.ProtocolAny)
	if err != nil {
		return "", err
	}
	defer card.Disconnect(scard.LeaveCard)

	resp, err := card.Transmit([]byte(getUIDApdu))
	if err != nil {
		return "", err
	}
	if len(resp) < 2 {
		return "", fmt.Errorf("get UID: short response (%d bytes)", len(resp))
	}
	sw1, sw2 := resp[len(resp)-2], resp[len(resp)-1]
	if sw1 != 0x90 || sw2 != 0x00 {
		return "", fmt.Errorf("get UID: card returned SW %02X%02X", sw1, sw2)
	}
	uid := resp[:len(resp)-2]
	return strings.ToUpper(hex.EncodeToString(uid)), nil
}
