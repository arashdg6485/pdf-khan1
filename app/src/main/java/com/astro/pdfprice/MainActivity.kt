
package com.astro.pdfprice

import android.app.Activity
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class PdfItem(val uri: Uri, val name: String)
data class PriceResult(
    val file: String, val page: Int, val code: String, val name: String,
    val description: String, val price: String, val score: Int, val context: String


class MainActivity : AppCompatActivity() {
    private val pdfs = mutableListOf<PdfItem>()
    private val allText = mutableMapOf<String, List<PageText>>()
    private lateinit var fileAdapter: FileAdapter
    private lateinit var resultAdapter: ResultAdapter
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var resultCount: TextView
    private val prefs by lazy { getSharedPreferences("pdfs", MODE_PRIVATE) }
    private val ocrEngine by lazy { OcrEngine(this) }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        var added = 0
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            if (pdfs.none { it.uri == uri }) {
                pdfs += PdfItem(uri, displayName(uri))
                added++
            }
        }
        persistPdfs()
        fileAdapter.notifyDataSetChanged()
        status.text = "$added فایل اضافه شد. برای استخراج متن و OCR اسکن‌شده، دکمه پردازش را بزنید."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        buildUi()
        restorePdfs()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20,18,20,12)
            setBackgroundColor(Color.rgb(248,250,252))
        }
        val title = TextView(this).apply {
            text = "جستجوی هوشمند قیمت ابزار"
            textSize = 24f; setTextColor(Color.rgb(15,23,42)); setTypeface(typeface,1)
            gravity = Gravity.RIGHT
        }
        val sub = TextView(this).apply {
            text = "جستجو در نام، شرح، کد کالا، مدل، مشخصات و قیمت PDFها"
            textSize = 14f; setTextColor(Color.DKGRAY); gravity = Gravity.RIGHT
            setPadding(0,4,0,14)
        }
        root.addView(title, lp())
        root.addView(sub, lp())

        val add = Button(this).apply { text = "＋ افزودن فایل‌های PDF"; setOnClickListener { picker.launch(arrayOf("application/pdf")) } }
        root.addView(add, lp())

        val process = Button(this).apply {
            text = "⚙ پردازش PDF + OCR هوشمند"
            setOnClickListener { processAll() }
        }
        root.addView(process, lp())

        val fileHeader = TextView(this).apply {
            text = "فایل‌های اضافه‌شده"
            textSize=17f; setTypeface(typeface,1); setPadding(0,14,0,6); gravity=Gravity.RIGHT
        }
        root.addView(fileHeader,lp())

        val filesRv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            fileAdapter = FileAdapter(pdfs) { index ->
                pdfs.removeAt(index); persistPdfs(); fileAdapter.notifyDataSetChanged()
            }
            adapter = fileAdapter
            isNestedScrollingEnabled = false
        }
        root.addView(filesRv, LinearLayout.LayoutParams(-1, dp(155)))

        query = EditText(this).apply {
            hint = "مثلاً: دریل شارژی 21 ولت /  DW / کد 12345 / بتن‌کن"
            textSize=16f
            setSingleLine(true)
            setGravity(Gravity.RIGHT)
            setPadding(16,8,16,8)
        }
        root.addView(query, lp())

        val search = Button(this).apply {
            text = "🔎 جستجوی هوشمند"
            setOnClickListener { searchNow() }
        }
        root.addView(search,lp())

        resultCount = TextView(this).apply { textSize=14f; setTextColor(Color.DKGRAY); gravity=Gravity.RIGHT; setPadding(0,6,0,4) }
        root.addView(resultCount,lp())
        status = TextView(this).apply { text="آماده؛ PDFهای کاتالوگ و لیست قیمت را اضافه کنید."; textSize=13f; setTextColor(Color.GRAY); gravity=Gravity.RIGHT; setPadding(0,3,0,8) }
        root.addView(status,lp())

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            resultAdapter = ResultAdapter()
            adapter=resultAdapter
        }
        root.addView(rv, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }

    private fun lp() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()

    private fun restorePdfs() {
        val set = prefs.getStringSet("uris", emptySet()) ?: emptySet()
        set.forEach { s ->
            try { pdfs += PdfItem(Uri.parse(s), displayName(Uri.parse(s))) } catch (_:Exception){}
        }
        fileAdapter.notifyDataSetChanged()
    }
    private fun persistPdfs() {
        prefs.edit().putStringSet("uris", pdfs.map { it.uri.toString() }.toSet()).apply()
    }
    private fun displayName(uri: Uri): String {
        return contentResolver.query(uri, arrayOf("_display_name"), null,null,null)?.use {
            if (it.moveToFirst()) it.getString(0) else "PDF"
        } ?: uri.lastPathSegment ?: "PDF"
    }

    private fun processAll() {
        if (pdfs.isEmpty()) { status.text="ابتدا حداقل یک PDF اضافه کنید."; return }
        status.text="در حال استخراج متن؛ صفحات اسکن‌شده با OCR فارسی/عربی خوانده می‌شوند..."
        Thread {
            val local = mutableMapOf<String,List<PageText>>()
            var scannedPages = 0
            var failed = 0
            try {
                // Download OCR models only when needed. This keeps normal text PDFs fast.
                var ocrReady = false
                pdfs.forEach { item ->
                    try {
                        val direct = contentResolver.openInputStream(item.uri)?.use { input -> extractPages(input) }.orEmpty()
                        val merged = direct.toMutableList()
                        val pageCount = getPdfPageCount(item.uri)
                        if (pageCount > 0 && merged.size < pageCount) {
                            if (!ocrReady) {
                                runOnUiThread { status.text="در حال آماده‌سازی OCR فارسی/عربی/انگلیسی..." }
                                ocrEngine.ensureModels { msg -> runOnUiThread { status.text = msg } }
                                ocrReady = true
                            }
                            val existing = merged.associateBy { it.pageNumber }
                            for (page in 1..pageCount) {
                                if (existing[page]?.text?.isNotBlank() == true) continue
                                runOnUiThread { status.text="OCR صفحه $page از $pageCount: ${item.name}" }
                                val text = ocrEngine.recognizePage(item.uri, page - 1)
                                if (text.isNotBlank()) {
                                    merged += PageText(page, text)
                                    scannedPages++
                                }
                            }
                        }
                        local[item.uri.toString()] = merged.sortedBy { it.pageNumber }
                    } catch (_: Exception) { failed++ }
                }
            } catch (_: Exception) { failed++ }
            runOnUiThread {
                allText.clear(); allText.putAll(local)
                val pages=local.values.sumOf { it.size }
                status.text="پردازش کامل شد: ${local.size} فایل، $pages صفحه قابل جستجو؛ $scannedPages صفحه با OCR خوانده شد${if (failed>0) "؛ $failed فایل/بخش ناموفق" else ""}."
            }
        }.start()
    }

    private fun getPdfPageCount(uri: Uri): Int {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { it.pageCount }
            } ?: 0
        } catch (_: Exception) { 0 }
    }

    private fun extractPages(input: InputStream): List<PageText> {
        PDDocument.load(input).use { doc ->
            val out=mutableListOf<PageText>()
            for (p in 1..doc.numberOfPages) {
                val stripper=PDFTextStripper().apply { startPage=p; endPage=p; sortByPosition=true }
                val text=stripper.getText(doc)
                if (text.isNotBlank()) out += PageText(p,text)
            }
            return out
        }
    }

    private fun searchNow() {
        val q = query.text.toString().trim()
        if (q.isBlank()) { resultAdapter.setItems(emptyList()); resultCount.text=""; return }
        if (allText.isEmpty()) { status.text="ابتدا «پردازش و ساخت شاخص هوشمند» را بزنید."; return }
        val results=mutableListOf<PriceResult>()
        pdfs.forEach { item ->
            allText[item.uri.toString()].orEmpty().forEach { page ->
                val lines=page.text.lines().map { normalize(it) }.filter { it.isNotBlank() }
                val qn=normalize(q)
                for (i in lines.indices) {
                    val line=lines[i]
                    val score=smartScore(qn,line)
                    if (score>=28) {
                        val context=buildString {
                            append(lines.getOrNull(max(0,i-1)).orEmpty()).append('\n')
                            append(lines.getOrNull(i).orEmpty()).append('\n')
                            append(lines.getOrNull(i+1).orEmpty())
                        }
                        val price=extractPrice(context)
                        val code=extractCode(context)
                        val name=bestName(lines,i)
                        val desc=lines.getOrNull(i)?.take(180).orEmpty()
                        results += PriceResult(item.name,page.pageNumber,code,name,desc,price,score,context)
                    }
                }
            }
        }
        val ranked=results.distinctBy { "${it.file}|${it.page}|${it.code}|${it.name}|${it.price}" }
            .sortedByDescending { it.score }.take(150)
        resultAdapter.setItems(ranked)
        resultCount.text="${ranked.size} نتیجه"
        status.text=if(ranked.isEmpty()) "نتیجه‌ای پیدا نشد؛ نام، مدل یا کد دقیق‌تر را امتحان کنید." else "نتایج بر اساس تطبیق نام/شرح/کد/مشخصات رتبه‌بندی شده‌اند."
    }

    private fun normalize(s:String):String = s
        .replace('ي','ی').replace('ى','ی').replace('ك','ک')
        .replace('ۀ','ه').replace('ة','ه').replace('\u0640',' ')
        .replace(Regex("[۰-۹]")) { ('0'.code + (it.value[0].code-0x06F0)).toChar().toString() }
        .replace(Regex("[٠-٩]")) { ('0'.code + (it.value[0].code-0x0660)).toChar().toString() }
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}.+\\-]+")," ").trim()

    private fun tokens(s:String)=normalize(s).split(" ").filter{it.length>=2}.toSet()

    private fun smartScore(q:String, text:String):Int {
        if (text.contains(q)) return 100
        val qt=tokens(q); val tt=tokens(text)
        if(qt.isEmpty()) return 0
        var hit=0
        qt.forEach { a ->
            if(tt.any { b -> b==a || b.contains(a) || a.contains(b) || editSimilarity(a,b)>=0.78 }) hit++
        }
        val base=(hit*100/qt.size)
        val numberBoost=if(q.filter{it.isDigit()}.isNotBlank() && q.filter{it.isDigit()}.all{it in text}) 22 else 0
        return min(100,base+numberBoost)
    }

    private fun editSimilarity(a:String,b:String):Double {
        if(a==b) return 1.0
        if(a.isEmpty()||b.isEmpty()) return 0.0
        val prev=IntArray(b.length+1){it}
        for(i in a.indices){
            val cur=IntArray(b.length+1); cur[0]=i+1
            for(j in b.indices) cur[j+1]=min(min(cur[j]+1,prev[j+1]+1),prev[j]+if(a[i]==b[j])0 else 1)
            for(j in prev.indices) prev[j]=cur[j]
        }
        return 1.0-prev[b.length].toDouble()/max(a.length,b.length)
    }

    private fun extractPrice(s:String):String {
        val n=normalize(s)
        val patterns=listOf(
            Regex("""(?:قیمت|مبلغ|تومان|ریال|تومانـ|price)[^0-9]{0,25}([0-9][0-9,.\s]{2,})""",RegexOption.IGNORE_CASE),
            Regex("""([0-9]{1,3}(?:[,.\s][0-9]{3}){1,4})\s*(?:تومان|ریال)?""")
        )
        for(p in patterns){
            val m=p.find(n) ?: continue
            return m.groupValues.last().replace(" ","").trim()
        }
        return "—"
    }

    private fun extractCode(s:String):String {
        val n=normalize(s)
        val m=Regex("""(?:کد|code|sku|مدل|model)\s*[:#\-]?\s*([a-z0-9][a-z0-9._\-]{2,})""",RegexOption.IGNORE_CASE).find(n)
        return m?.groupValues?.get(1) ?: Regex("""\b[A-Z]{1,5}[-]?[0-9]{3,}[A-Z0-9-]*\b""").find(s)?.value ?: "—"
    }
    private fun bestName(lines:List<String>,i:Int):String {
        val x=lines.getOrNull(i).orEmpty()
        return x.take(100).ifBlank { lines.getOrNull(i+1).orEmpty().take(100) }
    }
}

data class PageText(val pageNumber:Int,val text:String)

class FileAdapter(private val items:MutableList<PdfItem>, private val onDelete:(Int)->Unit):
    RecyclerView.Adapter<FileAdapter.VH>() {
    class VH(v:View):RecyclerView.ViewHolder(v) {
        val text=v.findViewById<TextView>(android.R.id.text1)
        val del=v.findViewById<Button>(android.R.id.button1)
    }
    override fun onCreateViewHolder(p:ViewGroup,t:Int):VH {
        val row=LinearLayout(p.context).apply{orientation=LinearLayout.HORIZONTAL;setPadding(4,4,4,4)}
        val tv=TextView(p.context, null, android.R.attr.textAppearanceMedium).apply{id=android.R.id.text1;layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
        val b=Button(p.context).apply{id=android.R.id.button1;text="حذف";setTextColor(Color.rgb(185,28,28))}
        row.addView(tv);row.addView(b);return VH(row)
    }
    override fun onBindViewHolder(h:VH,pos:Int){h.text.text="📄 ${items[pos].name}";h.del.setOnClickListener{onDelete(h.bindingAdapterPosition)}}
    override fun getItemCount()=items.size
}

class ResultAdapter:RecyclerView.Adapter<ResultAdapter.VH>() {
    private val items=mutableListOf<PriceResult>()
    class VH(v:View):RecyclerView.ViewHolder(v){val t=v.findViewById<TextView>(android.R.id.text1)}
    override fun onCreateViewHolder(p:ViewGroup,t:Int):VH{
        val tv=TextView(p.context).apply{ id=android.R.id.text1;setPadding(12,12,12,12);setTextSize(14f);setTextColor(Color.rgb(15,23,42));setBackgroundColor(Color.WHITE)}
        return VH(tv)
    }
    override fun onBindViewHolder(h:VH,pos:Int){
        val x=items[pos]
        h.t.text="کالا: ${x.name}\nکد/مدل: ${x.code}   |   قیمت: ${x.price}\nفایل: ${x.file}   |   صفحه: ${x.page}   |   تطبیق: ${x.score}%\n${x.description}"
    }
    override fun getItemCount()=items.size
    fun setItems(v:List<PriceResult>){items.clear();items.addAll(v);notifyDataSetChanged()}
}
