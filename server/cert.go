package main

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"log"
	"math/big"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

type certFiles struct {
	dir       string
	caCert    string
	serverKey string
	serverCrt string
}

type firefoxTrustResult struct {
	certutilFound bool
	profiles      []string
	failures      []string
}

func certPaths(stateDir string) certFiles {
	dir := filepath.Join(stateDir, "certs")
	return certFiles{
		dir:       dir,
		caCert:    filepath.Join(dir, "rootCA.pem"),
		serverKey: filepath.Join(dir, "server.key"),
		serverCrt: filepath.Join(dir, "server.crt"),
	}
}

func runCertSetup(cfg Config, force bool, logger *log.Logger) (certFiles, error) {
	files, generated, err := ensureCert(cfg.StateDir, force, logger)
	if err != nil {
		return files, err
	}
	trust := tryTrustFirefox(files.caCert)
	printCertSetup(files, cfg.WSSPort, generated, trust)
	return files, nil
}

func ensureCert(stateDir string, force bool, logger *log.Logger) (certFiles, bool, error) {
	files := certPaths(stateDir)
	if !force && fileExists(files.caCert) && fileExists(files.serverKey) && certStillValid(files.serverCrt) {
		return files, false, nil
	}

	if err := os.MkdirAll(files.dir, 0o700); err != nil {
		return files, false, err
	}

	caKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return files, false, err
	}
	serverKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return files, false, err
	}

	now := time.Now()
	caTemplate := &x509.Certificate{
		SerialNumber:          serialNumber(),
		Subject:               pkix.Name{CommonName: "Poliswag Local CA"},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &caKey.PublicKey, caKey)
	if err != nil {
		return files, false, err
	}

	serverTemplate := &x509.Certificate{
		SerialNumber: serialNumber(),
		Subject:      pkix.Name{CommonName: "127.0.0.1"},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.AddDate(2, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     []string{"localhost"},
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
	}
	serverDER, err := x509.CreateCertificate(rand.Reader, serverTemplate, caTemplate, &serverKey.PublicKey, caKey)
	if err != nil {
		return files, false, err
	}

	if err := writePEM(files.caCert, "CERTIFICATE", caDER, 0o644); err != nil {
		return files, false, err
	}
	if err := writePEM(files.serverCrt, "CERTIFICATE", serverDER, 0o644); err != nil {
		return files, false, err
	}
	keyDER := x509.MarshalPKCS1PrivateKey(serverKey)
	if err := writePEM(files.serverKey, "RSA PRIVATE KEY", keyDER, 0o600); err != nil {
		return files, false, err
	}

	if logger != nil {
		logger.Printf("generated local TLS cert: %s", files.serverCrt)
	}
	return files, true, nil
}

// certStillValid reports whether the server cert parses and is valid for at least
// another 30 days, so an expired or corrupt cert is replaced instead of silently
// breaking wss.
func certStillValid(path string) bool {
	data, err := os.ReadFile(path)
	if err != nil {
		return false
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return false
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return false
	}
	return time.Now().Add(30 * 24 * time.Hour).Before(cert.NotAfter)
}

func writePEM(path, typ string, bytes []byte, perm os.FileMode) error {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, perm)
	if err != nil {
		return err
	}
	defer f.Close()
	return pem.Encode(f, &pem.Block{Type: typ, Bytes: bytes})
}

func serialNumber() *big.Int {
	limit := new(big.Int).Lsh(big.NewInt(1), 128)
	n, err := rand.Int(rand.Reader, limit)
	if err != nil {
		return big.NewInt(time.Now().UnixNano())
	}
	return n
}

func tryTrustFirefox(caFile string) firefoxTrustResult {
	var result firefoxTrustResult
	certutil, ok := findNSSCertutil()
	if !ok {
		return result
	}
	result.certutilFound = true

	for _, root := range firefoxProfileRoots() {
		entries, err := os.ReadDir(root)
		if err != nil {
			continue
		}
		for _, entry := range entries {
			profile := filepath.Join(root, entry.Name())
			if !fileExists(filepath.Join(profile, "cert9.db")) {
				continue
			}
			db := "sql:" + profile
			_ = exec.Command(certutil, "-D", "-n", "Poliswag Local CA", "-d", db).Run()
			err := exec.Command(certutil, "-A", "-n", "Poliswag Local CA", "-t", "C,,", "-i", caFile, "-d", db).Run()
			if err != nil {
				result.failures = append(result.failures, fmt.Sprintf("%s: %v", profile, err))
				continue
			}
			result.profiles = append(result.profiles, profile)
		}
	}
	return result
}

func findNSSCertutil() (string, bool) {
	path, err := exec.LookPath("certutil")
	if err != nil {
		return "", false
	}
	out, _ := exec.Command(path, "-H").CombinedOutput()
	text := strings.ToLower(string(out))
	if strings.Contains(text, "nss") || strings.Contains(text, "certutil -a") {
		return path, true
	}
	return "", false
}

func firefoxProfileRoots() []string {
	home, _ := os.UserHomeDir()
	var roots []string
	if home == "" {
		return roots
	}

	switch runtime.GOOS {
	case "darwin":
		roots = append(roots,
			filepath.Join(home, "Library", "Application Support", "Firefox", "Profiles"),
			filepath.Join(home, "Library", "Application Support", "Zen", "Profiles"),
			filepath.Join(home, "Library", "Application Support", "LibreWolf", "Profiles"),
			filepath.Join(home, "Library", "Application Support", "Waterfox", "Profiles"),
			filepath.Join(home, "Library", "Application Support", "Floorp", "Profiles"),
		)
	case "windows":
		appData := os.Getenv("APPDATA")
		if appData != "" {
			roots = append(roots,
				filepath.Join(appData, "Mozilla", "Firefox", "Profiles"),
				filepath.Join(appData, "Zen", "Profiles"),
				filepath.Join(appData, "LibreWolf", "Profiles"),
				filepath.Join(appData, "Waterfox", "Profiles"),
				filepath.Join(appData, "Floorp", "Profiles"),
			)
		}
	default:
		roots = append(roots,
			filepath.Join(home, ".mozilla", "firefox"),
			filepath.Join(home, ".config", "mozilla", "firefox"),
			filepath.Join(home, "snap", "firefox", "common", ".mozilla", "firefox"),
			filepath.Join(home, ".var", "app", "org.mozilla.firefox", ".mozilla", "firefox"),
			filepath.Join(home, ".zen"),
			filepath.Join(home, ".config", "zen"),
			filepath.Join(home, ".var", "app", "app.zen_browser.zen", ".zen"),
			filepath.Join(home, ".librewolf"),
			filepath.Join(home, ".waterfox"),
			filepath.Join(home, ".floorp"),
		)
	}
	return roots
}

func printCertSetup(files certFiles, wssPort int, generated bool, trust firefoxTrustResult) {
	status := "Using existing"
	if generated {
		status = "Generated"
	}
	fmt.Printf("%s local certificate files:\n", status)
	fmt.Printf("  CA cert:     %s\n", files.caCert)
	fmt.Printf("  Server cert: %s\n", files.serverCrt)
	fmt.Printf("  Server key:  %s\n", files.serverKey)
	fmt.Println()
	if trust.certutilFound && len(trust.profiles) > 0 {
		fmt.Println("Trusted the local CA in these Firefox-family profiles:")
		for _, profile := range trust.profiles {
			fmt.Printf("  %s\n", profile)
		}
	} else {
		fmt.Println("Automatic Firefox/Zen trust was not completed.")
		if !trust.certutilFound {
			fmt.Println("  NSS certutil was not found.")
			fmt.Println()
			fmt.Println(certutilInstallHint())
		}
		fmt.Printf("  Import this CA cert into Firefox/Zen Authorities if needed: %s\n", files.caCert)
	}
	if len(trust.failures) > 0 {
		fmt.Println()
		fmt.Println("Some profiles could not be updated:")
		for _, failure := range trust.failures {
			fmt.Printf("  %s\n", failure)
		}
		fmt.Println("Close Firefox/Zen and rerun this command, or import the CA cert manually.")
	}
	fmt.Println()
	fmt.Println("Start or restart Poliswag Passport after this. It will use these files automatically and listen on:")
	fmt.Printf("  wss://127.0.0.1:%d/ws\n", wssPort)
	fmt.Println()
	fmt.Println("To use different files, set TLS_KEY and TLS_CERT before starting the helper.")
}

func certutilInstallHint() string {
	switch runtime.GOOS {
	case "darwin":
		return "Install NSS certutil with Homebrew if you want automatic Firefox/Zen trust setup: brew install nss"
	case "windows":
		return "For automatic Firefox/Zen trust setup on Windows, install NSS tools with certutil in PATH. Manual import is usually simpler."
	default:
		return "Install NSS certutil if you want automatic Firefox/Zen trust setup: Debian/Ubuntu: sudo apt install libnss3-tools; Fedora/RHEL: sudo dnf install nss-tools; Arch: sudo pacman -S nss"
	}
}
