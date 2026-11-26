package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// version is set at release time with -ldflags "-X main.version=<tag>".
var version = "1.1.0"

type Config struct {
	Host       string
	Port       int
	WSSPort    int
	JobTimeout time.Duration
	AuthSecret string
	// WorkerToken lets workers on other machines (the Android app, a remote browser)
	// connect to /ws. Empty means only local extension pages can be workers.
	WorkerToken string
	// LocalWorkers lets loopback extension pages connect without the token. Turn it
	// off when a reverse proxy on this host forwards outside traffic to the helper.
	LocalWorkers bool
	StateDir     string
	TLSKey       string
	TLSCert      string
	StatsFile    string
}

func main() {
	loadEnvFiles()
	cfg := loadConfig()
	logger := log.New(os.Stdout, "", log.LstdFlags)

	cmd := "serve"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}

	switch cmd {
	case "serve", "start":
		if err := serve(cfg, logger); err != nil {
			logger.Fatal(err)
		}
	case "chrome", "chromium":
		if err := serve(cfg, logger); err != nil {
			logger.Fatal(err)
		}
	case "firefox", "zen":
		files, err := runCertSetup(cfg, false, logger)
		if err != nil {
			logger.Fatal(err)
		}
		cfg.TLSKey = files.serverKey
		cfg.TLSCert = files.serverCrt
		if err := serve(cfg, logger); err != nil {
			logger.Fatal(err)
		}
	case "doctor", "status":
		doctor(cfg)
	case "healthcheck":
		// For container health checks (the image has no curl): exit 0 if the local
		// helper answers /health.
		if _, err := probeHealth(fmt.Sprintf("http://127.0.0.1:%d/health", cfg.Port)); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	case "cert":
		if _, err := runCertSetup(cfg, hasArg("--force"), logger); err != nil {
			logger.Fatal(err)
		}
	case "env":
		printEnv(cfg)
	case "version", "--version", "-v":
		fmt.Println(version)
	case "help", "--help", "-h":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "unknown command: %s\n\n", cmd)
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Println(`Usage: poliswag-passport [command]

Commands:
  serve     Run the local helper server in the foreground (default)
  chrome    Run the helper for Chrome/Chromium browsers
  firefox   Run Firefox/Zen cert setup, then run the helper
  doctor    Print setup checks and Dragonite URL hints
  cert      Generate a local 127.0.0.1 cert and try Firefox/Zen trust setup
  env       Print effective config
  healthcheck  Exit 0 if the local helper is up (for Docker)
  version   Print version

Environment:
  HOST=0.0.0.0
  PORT=5090
  WSS_PORT=5443
  JOB_TIMEOUT_MS=600000
  AUTH_SECRET=
  WORKER_TOKEN=
  LOCAL_WORKERS=true
  POLISWAG_STATE_DIR=
  TLS_KEY=
  TLS_CERT=`)
}

func loadConfig() Config {
	stateDir := defaultStateDir()
	tlsKey := os.Getenv("TLS_KEY")
	tlsCert := os.Getenv("TLS_CERT")
	if tlsKey == "" && tlsCert == "" {
		certs := certPaths(stateDir)
		if fileExists(certs.serverKey) && fileExists(certs.serverCrt) {
			tlsKey = certs.serverKey
			tlsCert = certs.serverCrt
		}
	}
	return Config{
		Host:         envString("HOST", "0.0.0.0"),
		Port:         envInt("PORT", 5090),
		WSSPort:      envInt("WSS_PORT", 5443),
		JobTimeout:   time.Duration(envInt("JOB_TIMEOUT_MS", 600000)) * time.Millisecond,
		AuthSecret:   os.Getenv("AUTH_SECRET"),
		WorkerToken:  os.Getenv("WORKER_TOKEN"),
		LocalWorkers: envBool("LOCAL_WORKERS", true),
		StateDir:     stateDir,
		TLSKey:       tlsKey,
		TLSCert:      tlsCert,
		StatsFile:    filepath.Join(stateDir, "stats.json"),
	}
}

func serve(cfg Config, logger *log.Logger) error {
	stats := NewStatsStore(cfg.StatsFile)
	app := NewApp(NewJobQueue(cfg.JobTimeout), cfg.AuthSecret, stats, logger, AppOptions{
		WorkerToken:  cfg.WorkerToken,
		NoLocalTrust: !cfg.LocalWorkers,
	})
	if cfg.WorkerToken == "" && !cfg.LocalWorkers {
		logger.Printf("warning: WORKER_TOKEN is unset and LOCAL_WORKERS=false; no worker can connect")
	}

	httpServer := &http.Server{
		Addr:              net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port)),
		Handler:           app.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	if cfg.AuthSecret == "" && !isLoopbackHost(cfg.Host) {
		logger.Printf("warning: AUTH_SECRET is unset and the helper listens on %s; anyone who can reach this port can request PTC logins. Keep the port on a private network, or set AUTH_SECRET if your caller can send it as an x-auth-secret header.", cfg.Host)
	}

	errCh := make(chan error, 2)
	go func() {
		logger.Printf("poliswag passport listening on http://%s (job timeout %s)", httpServer.Addr, cfg.JobTimeout)
		errCh <- httpServer.ListenAndServe()
	}()

	var wssServer *http.Server
	if cfg.TLSKey != "" || cfg.TLSCert != "" {
		if !fileExists(cfg.TLSKey) || !fileExists(cfg.TLSCert) {
			logger.Printf("wss disabled: TLS_KEY and TLS_CERT must both point to existing files")
		} else {
			wssServer = &http.Server{
				Addr:              net.JoinHostPort("127.0.0.1", strconv.Itoa(cfg.WSSPort)),
				Handler:           app.Handler(),
				ReadHeaderTimeout: 10 * time.Second,
			}
			go func() {
				logger.Printf("poliswag passport wss listening on wss://%s/ws", wssServer.Addr)
				errCh <- wssServer.ListenAndServeTLS(cfg.TLSCert, cfg.TLSKey)
			}()
		}
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)

	select {
	case sig := <-stop:
		logger.Printf("shutting down on %s", sig)
	case err := <-errCh:
		if err != nil && err != http.ErrServerClosed {
			return err
		}
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = httpServer.Shutdown(ctx)
	if wssServer != nil {
		_ = wssServer.Shutdown(ctx)
	}
	return nil
}

func doctor(cfg Config) {
	fmt.Println("Poliswag Passport doctor")
	fmt.Println()
	fmt.Println("Config:")
	fmt.Printf("  host:      %s\n", cfg.Host)
	fmt.Printf("  port:      %d\n", cfg.Port)
	fmt.Printf("  wss port:  %d\n", cfg.WSSPort)
	fmt.Printf("  state dir: %s\n", cfg.StateDir)
	fmt.Printf("  timeout:   %s\n", cfg.JobTimeout)
	fmt.Printf("  auth:      %s\n", setUnset(cfg.AuthSecret))
	fmt.Printf("  workers:   token %s, local extension %s\n", setUnset(cfg.WorkerToken), yesNo(cfg.LocalWorkers))
	fmt.Printf("  tls key:   %s\n", setUnset(cfg.TLSKey))
	fmt.Printf("  tls cert:  %s\n", setUnset(cfg.TLSCert))
	fmt.Println()

	fmt.Println("Local health:")
	healthURL := fmt.Sprintf("http://127.0.0.1:%d/health", cfg.Port)
	health, err := probeHealth(healthURL)
	if err != nil {
		fmt.Printf("  helper:    not reachable (%v)\n", err)
		fmt.Printf("  url:       %s\n", healthURL)
	} else {
		fmt.Printf("  helper:    running (%s)\n", healthURL)
		fmt.Printf("  workers:   %d connected\n", health.Workers)
		fmt.Printf("  pending:   %d\n", health.PendingJobs)
		fmt.Printf("  uptime:    %ds\n", health.UptimeSeconds)
	}
	fmt.Println()

	fmt.Println("Firefox/Zen secure WebSocket:")
	defaultCerts := certPaths(cfg.StateDir)
	fmt.Printf("  ca cert:   %s\n", pathStatus(defaultCerts.caCert))
	fmt.Printf("  tls cert:  %s\n", pathStatus(cfg.TLSCert))
	fmt.Printf("  tls key:   %s\n", pathStatus(cfg.TLSKey))
	if fileExists(cfg.TLSCert) && fileExists(cfg.TLSKey) {
		fmt.Printf("  wss:       ready on wss://127.0.0.1:%d/ws\n", cfg.WSSPort)
	} else {
		fmt.Println("  wss:       not configured; run `poliswag-passport cert` if Firefox/Zen needs it")
	}
	fmt.Println()

	fmt.Println("Dragonite direct LAN URLs:")
	ips := candidateIPs()
	if len(ips) == 0 {
		fmt.Printf("  remote_auth_url = \"http://<this-pc-lan-ip>:%d/api/v1/login-code\"\n", cfg.Port)
	} else {
		for _, ip := range ips {
			fmt.Printf("  remote_auth_url = \"http://%s:%d/api/v1/login-code\"  # %s\n", ip.address, cfg.Port, ip.label)
		}
	}
	fmt.Println()
	fmt.Println("Worker URLs (Android app, or the extension on another PC):")
	if cfg.WorkerToken == "" {
		fmt.Println("  set WORKER_TOKEN first; without it only the extension on this PC can connect")
	} else if len(ips) == 0 {
		fmt.Printf("  ws://<this-pc-lan-ip>:%d/ws  (worker token: the value of WORKER_TOKEN)\n", cfg.Port)
	} else {
		for _, ip := range ips {
			fmt.Printf("  ws://%s:%d/ws  # %s\n", ip.address, cfg.Port, ip.label)
		}
		fmt.Println("  worker token: the value of WORKER_TOKEN")
	}
	fmt.Println()
	fmt.Println("Reverse SSH tunnel option (no inbound port on this PC):")
	fmt.Printf("  ssh -N -R 127.0.0.1:%d:127.0.0.1:%d <user>@<dragonite-host-ip>\n", cfg.Port, cfg.Port)
	fmt.Printf("  remote_auth_url = \"http://127.0.0.1:%d/api/v1/login-code\"\n", cfg.Port)
}

func printEnv(cfg Config) {
	fmt.Printf("HOST=%s\n", cfg.Host)
	fmt.Printf("PORT=%d\n", cfg.Port)
	fmt.Printf("WSS_PORT=%d\n", cfg.WSSPort)
	fmt.Printf("JOB_TIMEOUT_MS=%d\n", cfg.JobTimeout.Milliseconds())
	fmt.Printf("AUTH_SECRET=%s\n", setUnset(cfg.AuthSecret))
	fmt.Printf("WORKER_TOKEN=%s\n", setUnset(cfg.WorkerToken))
	fmt.Printf("LOCAL_WORKERS=%t\n", cfg.LocalWorkers)
	fmt.Printf("POLISWAG_STATE_DIR=%s\n", cfg.StateDir)
	fmt.Printf("TLS_KEY=%s\n", cfg.TLSKey)
	fmt.Printf("TLS_CERT=%s\n", cfg.TLSCert)
}

type healthProbe struct {
	Status        string `json:"status"`
	Workers       int    `json:"workers"`
	PendingJobs   int    `json:"pending_jobs"`
	UptimeSeconds int    `json:"uptime_seconds"`
}

func probeHealth(url string) (healthProbe, error) {
	client := &http.Client{Timeout: time.Second}
	res, err := client.Get(url)
	if err != nil {
		return healthProbe{}, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return healthProbe{}, fmt.Errorf("HTTP %d", res.StatusCode)
	}
	var health healthProbe
	if err := json.NewDecoder(res.Body).Decode(&health); err != nil {
		return healthProbe{}, err
	}
	if health.Status != "OK" {
		return healthProbe{}, fmt.Errorf("status %q", health.Status)
	}
	return health, nil
}

func pathStatus(path string) string {
	if path == "" {
		return "unset"
	}
	if fileExists(path) {
		return path + " (present)"
	}
	return path + " (missing)"
}

func yesNo(value bool) string {
	if value {
		return "yes"
	}
	return "no"
}

type candidateIP struct {
	address string
	label   string
}

func candidateIPs() []candidateIP {
	var out []candidateIP
	ifaces, _ := net.Interfaces()
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip == nil || ip.To4() == nil {
				continue
			}
			address := ip.String()
			out = append(out, candidateIP{address: address, label: address + " (" + iface.Name + ")"})
		}
	}
	return out
}

func loadEnvFiles() {
	paths := []string{".env", filepath.Join("server", ".env")}
	if exe, err := os.Executable(); err == nil {
		paths = append(paths, filepath.Join(filepath.Dir(exe), ".env"))
	}
	for _, path := range paths {
		loadEnvFile(path)
	}
}

func loadEnvFile(path string) {
	file, err := os.Open(path)
	if err != nil {
		return
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key = strings.TrimSpace(key)
		if _, exists := os.LookupEnv(key); exists {
			continue
		}
		value = strings.Trim(strings.TrimSpace(value), `"'`)
		_ = os.Setenv(key, value)
	}
}

func envString(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func envInt(key string, fallback int) int {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	n, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return n
}

func isLoopbackHost(host string) bool {
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func envBool(key string, fallback bool) bool {
	v, err := strconv.ParseBool(os.Getenv(key))
	if err != nil {
		return fallback
	}
	return v
}

func setUnset(value string) string {
	if value == "" {
		return "unset"
	}
	return "set"
}

func fileExists(path string) bool {
	if path == "" {
		return false
	}
	_, err := os.Stat(path)
	return err == nil
}

func hasArg(arg string) bool {
	for _, a := range os.Args[2:] {
		if a == arg {
			return true
		}
	}
	return false
}
