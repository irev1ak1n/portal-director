# Performance

How PortalDirector is measured, what the numbers were before and after the engineering upgrade, and what is still unproven.

## How to measure

Two tools, both in the repository. Neither needs a plugin other than PortalDirector.

**Microbenchmarks (no server).** Times the planners, the scene writer and the explosion check on a synthetic flat world.

```powershell
.\gradlew benchmark -PbenchLabel=mylabel
```

Results are written to `build/benchmarks/<name>-mylabel.txt`.

**Live benchmark (real Paper server, bots).** Starts an isolated server on 127.0.0.1, builds and clears scenes of several sizes, runs TNT Strike and Sky Army with bots, restarts the server with a 250 portal scene and writes `bot-test/reports/benchmark-<label>.json`.

```powershell
cd bot-test
node benchmark.js --label mylabel --jar path\to\PortalDirector.jar --sizes 10,30,100,250 --players 10,20,40
```

Tick times come from the vanilla `tick query` command, which reports the last 100 ticks. The "worst 99th percentile" is the worst value seen across samples, not the single slowest tick. Bots are simulated players, not real clients, so they do not show what real clients cost the server (chunk sending, client lag, network).

For a real server, [Spark](https://spark.lucko.me) is the right tool: run `/spark profiler start`, do the thing, then `/spark profiler stop`. PortalDirector does not depend on Spark, and `/pd diagnostics` shows its own counters without it.

## Conditions

- Paper 26.3 build 231, `-Xmx3000M`, view and simulation distance 2, 127.0.0.1 only
- Intel Core Ultra 9 185H, 22 logical CPUs, 31 GB RAM, Windows 11
- Java 25 for the server, JDK 21 only to run Gradle
- 3 cycles for each portal count, 1 run each for TNT Strike, Sky Army and the restart
- The microbenchmarks report the median of 7 runs

## Results

"Before" is the 1.8.0 jar. "After" is the same code with the upgrade so far (background scene saves, atomic writes, portal index, chunk tickets that cannot leak, cached terrain during planning, diagnostics, SQLite journal).

### Planning only (microbenchmark)

| Portals | Before | After |
|---|---|---|
| 100 | 1.6 ms | 1.3 ms |
| 250 | 6.2 ms | 4.2 ms |
| 500 | 19.8 ms | 7.5 ms |
| 1000 | 81.7 ms | 14.6 ms |

Planning is no longer superlinear. It was already cheap below 500 portals, so players will only feel this on very large scenes.

### Saving a scene (microbenchmark)

| Portals | Before | After |
|---|---|---|
| 30 | 22.4 ms | 15.0 ms |
| 100 | 28.5 ms | 16.5 ms |
| 250 | 25.3 ms | 14.8 ms |
| 500 | 39.4 ms | 31.0 ms |

This microbenchmark times the file write itself, so it only shows that the faster file writer is a little cheaper. The real change is where the write runs: in the plugin the main thread now only takes a snapshot of the scene and a background thread writes it. **The main thread cost of that snapshot was not measured separately**, so the claim "saves no longer block the main thread" rests on the code and the tests, not on a number.

### Explosion check (microbenchmark)

| Portals | Before | After |
|---|---|---|
| 100 | 0.39 ms | 0.49 ms |
| 250 | 0.86 ms | 0.99 ms |
| 500 | 1.10 ms | 1.06 ms |
| 1000 | 0.55 ms | 0.92 ms |

No improvement. The differences are within the run to run noise of this machine, and the explosion check was already under 1.2 ms. The portal index was not shown to help here.

### Live server

| Portals | Build before | Build after | Clear before | Clear after | Worst 99th percentile tick before | after |
|---|---|---|---|---|---|---|
| 10 | 626 ms | 619 ms | 75 ms | 77 ms | 9796 ms | 283 ms |
| 30 | 1271 ms | 1275 ms | 131 ms | 126 ms | 52 ms | 39 ms |
| 100 | 2957 ms | 2910 ms | 303 ms | 312 ms | 37 ms | 27 ms |
| 250 | 4722 ms | 4678 ms | 662 ms | 668 ms | 62 ms | 28 ms |

The 10 portal rows include the first cycle while the world is still loading, which is why they are large. They vary a lot between runs and should not be compared.

Build and clear times did not change: they are limited by the per tick block budget and the delayed build, not by planning or saving. The worst tick at 100 and 250 portals is lower after, which fits the saves moving off the main thread, but this is one run with 3 cycles per size and is not strong evidence.

| Scenario | Before | After |
|---|---|---|
| TNT Strike, 20 portals, safe mode | 11.4 s, 137 minecarts, worst average tick 24.5 ms, cleanup 36 ms | 11.9 s, 156 minecarts, worst average tick 23.9 ms, cleanup 68 ms |
| Sky Army 10 players | ready in 6.2 s, worst 99th percentile 60 ms | 5.8 s, 94 ms |
| Sky Army 20 players | 8.9 s, 111 ms | 10.0 s, 95 ms |
| Sky Army 40 players | 18.8 s, 125 ms | 16.8 s, 104 ms |
| Restart with 250 portals | shutdown 2.3 s, all 250 back, clear 649 ms | shutdown 2.4 s, all 250 back, clear 640 ms |

The TNT minecart count differs because the strike is random. Sky Army differences are inside the noise of one run. Neither run logged an error or a warning.

## What this does and does not show

- Proven by measurement: planning scales much better (about 5.6 times faster at 1000 portals).
- Not shown by measurement: faster builds, faster clears, faster explosions, a smaller worst tick (one run, small effect).
- Shown by code and tests only: saves run in the background, written files are atomic, a cancelled job cannot leave a chunk ticket behind.
- Not tested: more than 40 simulated players, real clients, other servers, Folia, long running servers (hours), memory over time. The final heap numbers (13 MB before, 214 MB after) are a single reading taken at different points in garbage collection and mean nothing.
- The 100 or more real player case is not claimed. Bots do not render chunks or send input like real players.

## Recommendations

- Leave `performance.async-saves` on (the default).
- Keep the per tick block budget (`generate.blocks-per-tick`, 400 by default) low on a busy server. Build time grows with it, tick time grows with it too.
- Build very large scenes in several `/pd create` runs rather than one if the server is also busy with players.
- When something feels slow, run `/pd diagnostics` first, then a Spark profile.
