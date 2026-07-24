// builder.go implements the reviewer-suggested Builder abstraction:
//
//	type Builder interface {
//	    Build(cfg Config) (*Result, error)
//	}
//
// adapted for this project's actual shape. Two implementations already existed as
// separate, ad-hoc call paths before this file: main()'s dispatch had its own
// -connect-vs-normal branch, and runWatch's buildFunc closure had a comment noting it
// "abstracts over building locally ... vs. through a daemon" without an actual named
// type doing that abstracting. localBuilder and daemonBuilder below are that same
// abstraction, just given a name and a shared interface - main() and runWatch now
// hold a Builder and call Build, with no need to know or care which kind they got.
//
// Build doesn't take cfg as a parameter (unlike the literal suggestion above) because
// the two real implementations don't actually share a "just needs cfg" shape:
// localBuilder needs an already-loaded, CLI-flag-overridden *config (loaded once in
// main(), then reused every -watch tick); daemonBuilder needs only a daemon address
// and the raw config *path* (daemon.go's whole point is a thin client that doesn't
// even parse the config itself - the daemon does that server-side). Rather than
// forcing one of them into an unnatural shape (or changing the wire protocol just to
// make the interface prettier), each Builder captures what it needs at construction
// time, and Build only takes what varies per call: quiet.
package main

// Result is a build attempt's outcome, common to every Builder implementation so
// callers don't need to know which kind they're holding.
type Result struct {
	// DidBuild is true only if an actual arduino-cli compile ran - false on a cache
	// hit, or on a suppressed repeat-failure in quiet mode (see run() in
	// fastbuild.go for both cases).
	DidBuild bool
}

// Builder runs one build attempt and reports its outcome.
type Builder interface {
	// Build runs one build attempt. quiet suppresses "no changes"/cache-hit status
	// output entirely - used by the watch loop's repeated polling, where printing
	// "no changes" every single tick would be noise. See run() and
	// sendBuildToDaemon() for what "quiet" actually does in each implementation.
	Build(quiet bool) (*Result, error)
}

// localBuilder builds by invoking arduino-cli directly in this process - the normal,
// non-daemon path. cfg is expected to already have every CLI-flag override applied
// (main() does this once before constructing a localBuilder); Build passes it to run()
// unchanged on every call, including repeated -watch ticks.
type localBuilder struct {
	cfg *config
}

func (b localBuilder) Build(quiet bool) (*Result, error) {
	didBuild, err := run(b.cfg, quiet)
	return &Result{DidBuild: didBuild}, err
}

// daemonBuilder builds by sending a request to an already-running -daemon instance
// (-connect) instead of compiling in this process. Deliberately holds just addr and
// configPath, not a loaded *config - forwarding the raw path and letting the daemon
// load/parse it server-side is the whole point of the existing thin-client protocol
// in daemon.go (a -connect client never needs arduino-cli or the dependency-hashing
// machinery locally at all), and reshaping that just to fit a common Builder
// signature would give up a real property for no real benefit.
type daemonBuilder struct {
	addr       string
	configPath string
}

func (b daemonBuilder) Build(quiet bool) (*Result, error) {
	didBuild, err := sendBuildToDaemon(b.addr, b.configPath, quiet)
	return &Result{DidBuild: didBuild}, err
}
