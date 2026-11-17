// PostToolUse: run Spotless (google-java-format) on a Java file Claude just wrote/edited.
// Runs only against the owning Maven module, and never fails the tool call.
import { readFileSync, existsSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { spawnSync } from "node:child_process";

const input = JSON.parse(readFileSync(0, "utf8") || "{}");
const rawFile = input.tool_input?.file_path ?? input.tool_response?.filePath;
if (!rawFile || !rawFile.endsWith(".java") || !existsSync(rawFile)) process.exit(0);

const win = process.platform === "win32";
// resolve() normalizes separators; CLAUDE_PROJECT_DIR may use "/" on Windows.
const file = resolve(rawFile);
const root = resolve(process.env.CLAUDE_PROJECT_DIR ?? process.cwd());
const norm = (p) => (win ? p.toLowerCase() : p);
const inside = (p) => norm(p).startsWith(norm(root));
if (!inside(file)) process.exit(0);

// Nearest pom.xml above the file = owning module.
let dir = dirname(file);
while (inside(dir) && !existsSync(join(dir, "pom.xml"))) dir = dirname(dir);
if (!existsSync(join(dir, "pom.xml"))) process.exit(0);
const module = relative(root, dir) || ".";

// Spotless matches -DspotlessFiles as a regex against absolute paths.
const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const fileRegex = ".*" + relative(root, file).split(sep).map(escape).join("[\\\\/]");

const mvnw = win ? "mvnw.cmd" : "./mvnw";
const args = ["-q", "spotless:apply", `-DspotlessFiles=${fileRegex}`];
if (module !== ".") args.unshift("-pl", module);

const res = spawnSync(mvnw, args, { cwd: root, shell: win, encoding: "utf8", timeout: 120000 });
if (res.status !== 0) {
  process.stdout.write(JSON.stringify({ systemMessage: `spotless:apply failed for ${file} (non-blocking)` }));
}
process.exit(0);
