// PostToolUse: run Spotless (google-java-format) on a Java file Claude just wrote/edited.
// Runs only against the owning Maven module, and never fails the tool call.
import { readFileSync, existsSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { spawnSync } from "node:child_process";

const input = JSON.parse(readFileSync(0, "utf8") || "{}");
const file = input.tool_input?.file_path ?? input.tool_response?.filePath;
if (!file || !file.endsWith(".java") || !existsSync(file)) process.exit(0);

const root = process.env.CLAUDE_PROJECT_DIR ?? process.cwd();

// Nearest pom.xml above the file = owning module.
let dir = dirname(file);
while (dir.startsWith(root) && !existsSync(join(dir, "pom.xml"))) dir = dirname(dir);
if (!existsSync(join(dir, "pom.xml"))) process.exit(0);
const module = relative(root, dir) || ".";

// Spotless matches -DspotlessFiles as a regex against absolute paths.
const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const fileRegex = ".*" + relative(root, file).split(sep).map(escape).join("[\\\\/]");

const win = process.platform === "win32";
const mvnw = win ? "mvnw.cmd" : "./mvnw";
const args = ["-q", "spotless:apply", `-DspotlessFiles=${fileRegex}`];
if (module !== ".") args.unshift("-pl", module);

const res = spawnSync(mvnw, args, { cwd: root, shell: win, encoding: "utf8", timeout: 120000 });
if (res.status !== 0) {
  process.stdout.write(JSON.stringify({ systemMessage: `spotless:apply failed for ${file} (non-blocking)` }));
}
process.exit(0);
