// PreToolUse guard: blocks access to real .env files (only .env.example is allowed)
// and recursive deletes outside target/ or build/ directories.
// Exit code 2 blocks the tool call; stderr is shown to Claude.
import { readFileSync } from "node:fs";

const input = JSON.parse(readFileSync(0, "utf8") || "{}");
const tool = input.tool_name ?? "";
const ti = input.tool_input ?? {};

const block = (reason) => {
  process.stderr.write(`Blocked by .claude/hooks/guard.mjs: ${reason}\n`);
  process.exit(2);
};

const isRealEnvFile = (p) => {
  const base = String(p).split(/[\\/]/).pop();
  return /^\.env(\..+)?$/.test(base) && base !== ".env.example";
};

// File tools
for (const key of ["file_path", "path", "notebook_path"]) {
  if (ti[key] && isRealEnvFile(ti[key])) block(`access to ${ti[key]} is not allowed (secrets).`);
}
if (tool === "Glob" && /(^|[\\/])\.env(\.(?!example\b)[^\\/]*)?$/.test(ti.pattern ?? "")) {
  block("globbing for .env files is not allowed.");
}

// Shell tools
if (tool === "Bash" || tool === "PowerShell") {
  const cmd = String(ti.command ?? "");

  const envRef = /(^|[\s"'=<>(\\/])\.env(\.[\w.-]+)?(?=$|[\s"';|&>)])/g;
  for (const m of cmd.matchAll(envRef)) {
    if (m[2] !== ".example") block("shell access to .env files is not allowed (secrets).");
  }

  const safeDir = (p) => /(^|[\\/])(target|build)([\\/]|$)/.test(p.replace(/["']/g, ""));
  // bash: rm with -r and -f in any order/combination
  for (const seg of cmd.split(/&&|\|\||;|\|/)) {
    const words = seg.trim().split(/\s+/);
    if (words[0] === "rm") {
      const flags = words.filter((w) => w.startsWith("-")).join("");
      if (/r/i.test(flags) && /f/.test(flags)) {
        const targets = words.slice(1).filter((w) => !w.startsWith("-"));
        if (targets.length === 0 || !targets.every(safeDir)) {
          block("rm -rf is only allowed on target/ or build/ directories. Use ./mvnw clean instead.");
        }
      }
    }
    if (/^Remove-Item\b/i.test(seg.trim()) && /-Recurse\b/i.test(seg)) {
      const targets = words.slice(1).filter((w) => !w.startsWith("-"));
      if (targets.length === 0 || !targets.every(safeDir)) {
        block("Remove-Item -Recurse is only allowed on target/ or build/ directories.");
      }
    }
  }
}

process.exit(0);
