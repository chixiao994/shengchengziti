package com.svgfont.maker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.svgfont.maker.font.Glyph
import com.svgfont.maker.font.GlyphBuilder
import com.svgfont.maker.font.TrueTypeFontBuilder
import com.svgfont.maker.svg.SvgParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private enum class Status { PENDING, DONE, SKIPPED }

    private class Item(
        var uri: Uri,
        var name: String,
        var status: Status = Status.PENDING,
        var codepoint: Int = 0,
        var glyph: Glyph? = null
    )

    private val items = ArrayList<Item>()
    private var index = 0
    private var inputUri: Uri? = null
    private var outputUri: Uri? = null
    private var fontJob: Job? = null

    private lateinit var preview: GlyphPreviewView
    private lateinit var tvStatus: TextView
    private lateinit var tvChar: TextView
    private lateinit var etName: EditText

    private val pickInput = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uri = res.data?.data ?: return@registerForActivityResult
            persist(uri)
            inputUri = uri
            prefs().edit().putString("input", uri.toString()).apply()
            loadInput()
        }
    }

    private val pickOutput = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uri = res.data?.data ?: return@registerForActivityResult
            persist(uri)
            outputUri = uri
            prefs().edit().putString("output", uri.toString()).apply()
            toast("输出文件夹已设置")
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        tvStatus = findViewById(R.id.tvStatus)
        tvChar = findViewById(R.id.tvChar)
        etName = findViewById(R.id.etName)

        findViewById<Button>(R.id.btnInput).setOnClickListener {
            pickInput.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }
            )
        }

        findViewById<Button>(R.id.btnOutput).setOnClickListener {
            pickOutput.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }
            )
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { doSaveManual() }

        findViewById<Button>(R.id.btnRename).setOnClickListener {
            commitRename()
        }

        etName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitRename()
                true
            } else false
        }

        findViewById<Button>(R.id.btnPrev).setOnClickListener {
            commitRenameSilent()
            if (index > 0) { index--; updateUi() }
        }

        findViewById<Button>(R.id.btnNext).setOnClickListener {
            commitRenameSilent()
            val cur = items.getOrNull(index)
            if (cur != null && cur.status != Status.SKIPPED && cur.glyph != null) {
                cur.status = Status.DONE
                saveState()
                appendFontAsync()
            }
            if (index < items.size - 1) { index++; updateUi() } else updateUi()
        }

        findViewById<Button>(R.id.btnSkip).setOnClickListener {
            commitRenameSilent()
            val cur = items.getOrNull(index)
            if (cur != null) {
                if (cur.status == Status.DONE) {
                    cur.status = Status.SKIPPED
                    saveState()
                    appendFontAsync()
                } else {
                    cur.status = Status.SKIPPED
                    saveState()
                }
            }
            if (index < items.size - 1) { index++; updateUi() } else updateUi()
        }

        val p = prefs()
        p.getString("input", null)?.let { inputUri = Uri.parse(it) }
        p.getString("output", null)?.let { outputUri = Uri.parse(it) }

        if (inputUri != null) loadInput() else updateUi()
    }

    private fun prefs() = getSharedPreferences("svgfont", MODE_PRIVATE)

    private fun persist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
        }
    }

    // ================= 加载 =================

    private fun loadInput() {
        val uri = inputUri ?: return
        lifecycleScope.launch {
            tvStatus.text = "读取中…"
            val list = withContext(Dispatchers.IO) {
                try {
                    val tree = DocumentFile.fromTreeUri(this@MainActivity, uri)
                        ?: return@withContext emptyList<Item>()
                    tree.listFiles()
                        .filter { it.isFile && it.name?.lowercase()?.endsWith(".svg") == true }
                        .sortedBy { it.name ?: "" }
                        .map { Item(it.uri, it.name ?: "") }
                } catch (e: Exception) {
                    emptyList<Item>()
                }
            }
            items.clear()
            items.addAll(list)
            restoreState()
            assignCodepoints()
            index = 0
            updateUi()
            parseAll()
        }
    }

    private suspend fun parseAll() {
        withContext(Dispatchers.IO) {
            for (i in items.indices) {
                val item = items[i]
                if (item.glyph != null) continue
                try {
                    val text = contentResolver.openInputStream(item.uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                    if (text != null) {
                        val doc = SvgParser.parse(text)
                        item.glyph = GlyphBuilder.build(doc, item.codepoint)
                    }
                } catch (_: Exception) {}
                if (item.glyph == null && item.status == Status.PENDING) {
                    item.status = Status.SKIPPED
                }
                val idx = i
                withContext(Dispatchers.Main) {
                    if (idx == index) updateUi()
                }
            }
        }
        saveState()
        updateUi()
    }

    private suspend fun parseOne(item: Item) {
        withContext(Dispatchers.IO) {
            try {
                val text = contentResolver.openInputStream(item.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                if (text != null) {
                    val doc = SvgParser.parse(text)
                    item.glyph = GlyphBuilder.build(doc, item.codepoint)
                }
            } catch (_: Exception) {}
        }
    }

    // ================= 码点分配 =================

    private fun assignCodepoints() {
        val used = HashSet<Int>()
        for (it in items) if (it.codepoint > 0) used.add(it.codepoint)
        var next = 0xE000
        for (it in items) {
            if (it.codepoint > 0) continue
            val fromName = codepointFromName(it.name)
            if (fromName != null && fromName > 0 && fromName < 0x10FFFF && used.add(fromName)) {
                it.codepoint = fromName
            } else {
                while (used.contains(next)) next++
                it.codepoint = next
                used.add(next)
            }
        }
    }

    private fun codepointFromName(name: String): Int? {
        val base = name.substringBeforeLast('.')

        Regex("^[Uu]\\+?([0-9A-Fa-f]{4,6})$").find(base)?.let {
            return it.groupValues[1].toIntOrNull(16)
        }
        Regex("^uni([0-9A-Fa-f]{4,6})$", RegexOption.IGNORE_CASE).find(base)?.let {
            return it.groupValues[1].toIntOrNull(16)
        }

        if (base.isNotEmpty()) {
            val first = base.codePointAt(0)
            val cc = Character.charCount(first)
            if (base.length == cc && first > 0x20 && first != 0x7F) {
                return first
            }
        }

        var i = 0
        while (i < base.length) {
            val cp = base.codePointAt(i)
            if (cp in 0x4E00..0x9FFF ||
                cp in 0x3400..0x4DBF ||
                cp in 0x20000..0x2A6DF ||
                cp in 0x2A700..0x2B73F ||
                cp in 0x2B740..0x2B81F ||
                cp in 0x2B820..0x2CEAF ||
                cp in 0x30000..0x3134F
            ) {
                return cp
            }
            i += Character.charCount(cp)
        }

        return null
    }

    // ================= 重命名 =================

    private fun commitRename() {
        val item = items.getOrNull(index) ?: return
        val raw = etName.text.toString()
        renameCurrent(item, raw, true)
    }

    private fun commitRenameSilent() {
        val item = items.getOrNull(index) ?: return
        val raw = etName.text.toString().trim()
        val cur = item.name.substringBeforeLast('.')
        if (raw.isEmpty()) return
        val normalized = if (raw.lowercase().endsWith(".svg")) raw.substringBeforeLast('.') else raw
        if (normalized == cur) return
        renameCurrent(item, raw, false)
    }

    private fun renameCurrent(item: Item, rawName: String, showToast: Boolean) {
        var newName = rawName.trim()
        if (newName.isEmpty()) {
            if (showToast) toast("文件名不能为空")
            return
        }
        if (!newName.lowercase().endsWith(".svg")) newName += ".svg"

        // 检查重名
        for (other in items) {
            if (other !== item && other.name == newName) {
                if (showToast) toast("已存在同名文件：$newName")
                return
            }
        }

        val newCp = codepointFromName(newName)

        lifecycleScope.launch {
            val oldCp = item.codepoint

            // 尝试文件系统重命名（用 DocumentsContract 更稳）
            val renamed = withContext(Dispatchers.IO) {
                try {
                    val newUri = DocumentsContract.renameDocument(
                        contentResolver, item.uri, newName
                    )
                    if (newUri != null) {
                        item.uri = newUri
                        item.name = newName
                        true
                    } else {
                        Log.w("SvgFont", "renameDocument returned null for $newName")
                        false
                    }
                } catch (e: Exception) {
                    Log.e("SvgFont", "renameDocument failed", e)
                    false
                }
            }

            // 关键：无论文件是否重命名成功，都更新码点（字体生成不依赖文件名）
            if (newCp != null && newCp > 0 && newCp != oldCp) {
                item.codepoint = newCp
                item.glyph = null
                parseOne(item)
            }

            // 文件重命名失败时，仍保留用户输入的显示名，让用户看到自己填的东西
            // 状态保存时会同时记录 uri 和 name，下次加载按 uri 匹配，不会丢
            if (!renamed) {
                item.name = newName
            }

            saveState()
            updateUi()

            // 已完成的字且码点变了 → 重新生成字体
            if (item.codepoint != oldCp && item.status == Status.DONE) {
                appendFontAsync()
            }

            if (showToast) {
                if (renamed) {
                    toast("已重命名为 $newName")
                } else if (item.codepoint != oldCp) {
                    toast("已设为 U+${item.codepoint.toString(16).uppercase()}（文件未能改名）")
                } else {
                    toast("文件重命名失败")
                }
                hideKeyboard()
            }
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etName.windowToken, 0)
            etName.clearFocus()
        } catch (_: Exception) {}
    }

    // ================= 状态 =================

    private fun stateFile(): File {
        val key = (inputUri?.toString() ?: "none").hashCode().toString()
        return File(filesDir, "state_$key.json")
    }

    private fun saveState() {
        try {
            val arr = JSONArray()
            for (it in items) {
                val o = JSONObject()
                o.put("n", it.name)
                o.put("s", it.status.name)
                o.put("c", it.codepoint)
                // 记录文件名（可能有重命名失败的情况，用 name 匹配更稳）
                o.put("u", it.uri.toString())
                arr.put(o)
            }
            stateFile().writeText(arr.toString())
        } catch (_: Exception) {}
    }

    private fun restoreState() {
        try {
            val f = stateFile()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())

            // 同时按 name 和 uri 建索引
            val byName = HashMap<String, JSONObject>()
            val byUri = HashMap<String, JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                byName[o.getString("n")] = o
                o.optString("u", "").takeIf { it.isNotEmpty() }?.let { byUri[it] = o }
            }

            for (it in items) {
                // 优先按 uri 匹配（文件名可以不同，uri 唯一）
                val o = byUri[it.uri.toString()] ?: byName[it.name] ?: continue
                it.status = try {
                    Status.valueOf(o.getString("s"))
                } catch (_: Exception) { Status.PENDING }
                it.codepoint = o.optInt("c", 0)
                // 恢复用户设置过的显示名
                val savedName = o.optString("n", "")
                if (savedName.isNotEmpty() && savedName != it.name) {
                    // 只有当我们无法从文件系统拿到这个名字时才覆盖
                    // 因为文件系统的真名才是"真相"，但如果用户重命名过、
                    // 文件系统名和状态名不同，则以状态里的为准（用户意图）
                    it.name = savedName
                }
            }
        } catch (_: Exception) {}
    }

    // ================= UI =================

    private fun updateUi() {
        val item = items.getOrNull(index)
        if (item == null) {
            tvStatus.text = if (inputUri == null) "请选择输入文件夹" else "没有找到 SVG 文件"
            tvChar.text = ""
            etName.setText("")
            etName.isEnabled = false
            preview.setGlyph(null)
            return
        }
        etName.isEnabled = true
        val ch = try {
            String(Character.toChars(item.codepoint))
        } catch (_: Exception) { "?" }
        tvChar.text = ch
        if (!etName.hasFocus()) {
            etName.setText(item.name)
        }
        val outMark = if (outputUri == null) "  [未设输出]" else ""
        val doneCount = items.count { it.status == Status.DONE }
        tvStatus.text = "${index + 1}/${items.size}  U+${
            item.codepoint.toString(16).uppercase()
        }  ${item.status}  已入字体:$doneCount$outMark"
        preview.setGlyph(item.glyph)
    }

    // ================= 保存字体 =================

    private fun doSaveManual() {
        if (outputUri == null) {
            toast("请先选择输出文件夹")
            return
        }
        appendFontAsync()
    }

    private fun appendFontAsync() {
        val out = outputUri ?: return
        val glyphs = items.filter { it.status == Status.DONE }.mapNotNull { it.glyph }
        if (glyphs.isEmpty()) {
            toast("还没有确认过的字，字体未生成")
            return
        }
        fontJob?.cancel()
        fontJob = lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    TrueTypeFontBuilder("MySvgFont").build(glyphs)
                }
                withContext(Dispatchers.IO) {
                    writeFontFile(out, bytes)
                }
                toast("字体已更新：${glyphs.size} 个字")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("字体保存失败：${e.message}")
            }
        }
    }

    private fun writeFontFile(dirUri: Uri, bytes: ByteArray) {
        val tree = DocumentFile.fromTreeUri(this, dirUri)
            ?: throw IllegalStateException("无法访问输出文件夹")
        tree.findFile("svgfont.ttf")?.delete()
        val f = tree.createFile("application/octet-stream", "svgfont.ttf")
            ?: throw IllegalStateException("无法创建文件")
        contentResolver.openOutputStream(f.uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("无法写入文件")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
