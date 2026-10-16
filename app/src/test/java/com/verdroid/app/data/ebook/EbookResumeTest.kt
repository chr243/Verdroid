package com.verdroid.app.data.ebook

import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** E1 (append-only parts file, migration from the old JSON sidecar) and E2 (language-check retry never aborts). */
class EbookResumeTest {
    @Test fun sidecarAndLanguageRetry() {
        val dir = Files.createTempDirectory("ebook-resume").toFile()
        try {
            java.io.File(dir, "et.py").writeText(EbookTranslatePy.SOURCE)
            java.io.File(dir, "t.py").writeText(SCRIPT)
            val p = runCatching { ProcessBuilder("python3", "t.py").directory(dir).redirectErrorStream(true).start() }.getOrNull()
            assumeTrue("python3 not available", p != null)
            val out = p!!.inputStream.bufferedReader().readText(); p.waitFor()
            assertTrue(out, out.trim().endsWith("ok"))
        } finally { dir.deleteRecursively() }
    }

    private companion object {
        val SCRIPT = """
import importlib.util, json, io, contextlib
from pathlib import Path
spec = importlib.util.spec_from_file_location("et", "et.py"); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
d = Path("w"); d.mkdir()
sp = d / "o.txt.verdroid-translate.json"; pp = m.parts_path(sp)
st = {"total": 5, "done": 2, "parts": [[0, "a"], [0, "b"]]}
assert m.resume_parts(st, pp) == [[0, "a"], [0, "b"]] and st["done"] == 2 and "parts" not in st
m.save_state(sp, dict(st, parts=[1])); assert "parts" not in json.loads(sp.read_text())
m.append_part(pp, 1, "c\nd"); open(pp, "a").write('[1, "trunc')
st2 = {"total": 5, "done": 2}; p2 = m.resume_parts(st2, pp)
assert st2["done"] == 3 and p2[-1] == [1, "c\nd"], p2
m.need = lambda mods: []
m.DELAY = (0, 0); m.BATCH_PAUSE = (0, 0)
class E(m.Engines):
    def __init__(s): pass
    def _google(s, t, a, b): return "T:" + t[:10]
    def _mymemory(s, *x): raise RuntimeError("mymemory down")
    def close(s): pass
m.Engines = E
m.verify_lang = lambda t, dd: (False, "xx")
d = Path("run"); d.mkdir()
inp = d / "b.txt"; inp.write_text("\n\n".join("Paragraph %d with words." % i for i in range(40)) * 30)
buf = io.StringIO()
with contextlib.redirect_stdout(buf): m.main(["--input", str(inp), "--dest", "fr", "--output", str(d / "out.txt")])
res = json.loads([l for l in buf.getvalue().splitlines() if l.startswith(m.MARKER)][-1][len(m.MARKER):])
assert res["ok"] and (d / "out.txt").is_file(), res
assert not any(p.name.endswith((".json", ".parts")) for p in d.iterdir())
print("ok")
""".trimIndent()
    }
}
