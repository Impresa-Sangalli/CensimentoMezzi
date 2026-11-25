package it.impresasangalli.censimentomezzi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.DialogInterface
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_PERM_CAMERA = 2
        private const val REQ_PERM_STORAGE = 3

        val ZONE = listOf("A", "B", "C", "D", "E", "F", "G", "H1", "H2")

        private val GREEN = 0xFF1B7F3B.toInt()
        private val GREEN_DARK = 0xFF125A29.toInt()
        private val GREEN_LIGHT = 0xFFE6F2EA.toInt()
        private val RED = 0xFFC62828.toInt()
        private val ORANGE = 0xFFE65100.toInt()
        private val BLUE = 0xFF1565C0.toInt()
        private val GREY = 0xFF616161.toInt()
        private val BG = 0xFFF4F6F5.toInt()

        private val D_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val T_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
    }

    private lateinit var db: Db

    // Stato del censimento in corso
    private var zona: String? = null
    private var dataUscita: LocalDate? = null
    private var filtroElenco = 0   // 0 tutti, 1 nel piazzale, 2 usciti
    private var photo: File? = null
    private var pendingPhoto: String? = null
    private var pendingStorageAction: (() -> Unit)? = null
    private var tab = 0
    private var saving = false

    // Viste
    private lateinit var tvHeaderCount: TextView
    private val tabButtons = ArrayList<Button>()
    private val screens = ArrayList<View>()
    private lateinit var etTarga: EditText
    private lateinit var tvDup: TextView
    private val zoneButtons = HashMap<String, Button>()
    private lateinit var ivPreview: ImageView
    private lateinit var btnFoto: Button
    private lateinit var tvErr: TextView
    private lateinit var tvLast: TextView
    private lateinit var btnUscita: Button
    private lateinit var btnDupUscita: Button
    private lateinit var btnUscitaClear: Button
    private val filterButtons = ArrayList<Button>()
    private lateinit var btnSalva: Button
    private lateinit var etSearch: EditText
    private lateinit var tvListCount: TextView
    private lateinit var listAdapter: CensAdapter
    private lateinit var tvSummary: TextView
    private lateinit var tvSync: TextView
    private lateinit var tvOnlineStatus: TextView
    private lateinit var btnLogin: Button
    private lateinit var btnSyncNow: Button
    private lateinit var btnRetry: Button
    private lateinit var cfgBox: LinearLayout
    private lateinit var btnCfgToggle: Button
    private lateinit var etTenant: EditText
    private lateinit var etClient: EditText
    private lateinit var etSite: EditText
    private lateinit var etFolder: EditText
    private lateinit var etExcel: EditText
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val syncListener: () -> Unit = {
        uiHandler.post {
            refreshSyncStatus()
            if (tab == 1) refreshList()
            if (tab == 3) refreshOnline()
        }
    }
    private val zoneCountViews = HashMap<String, TextView>()

    // ------------------------------------------------------------------ ciclo di vita

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        window.statusBarColor = GREEN_DARK

        var savedTarga = ""
        if (savedInstanceState != null) {
            savedTarga = savedInstanceState.getString("targa", "")
            zona = savedInstanceState.getString("zona")
            savedInstanceState.getString("uscita")?.let { dataUscita = LocalDate.parse(it) }
            filtroElenco = savedInstanceState.getInt("filtro", 0)
            savedInstanceState.getString("photo")?.let { p -> File(p).takeIf { it.exists() }?.let { photo = it } }
            pendingPhoto = savedInstanceState.getString("pendingPhoto")
            tab = savedInstanceState.getInt("tab", 0)
        } else {
            cleanupTemp()
        }

        setContentView(buildUi())
        etTarga.setText(savedTarga)
        updateZoneButtons()
        updatePreview()
        updateUscitaButton()
        updateFilterButtons()
        showTab(tab)

        handleAuthRedirect(intent)

        // Android 8-9: permesso di scrittura per creare Pictures/CensimentoMezzi
        if (PhotoStore.legacy) {
            if (hasStoragePermission()) PhotoStore.ensureFolder()
            else if (savedInstanceState == null) requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), REQ_PERM_STORAGE
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        SyncEngine.listener = syncListener
        refreshSyncStatus()
        if (tab == 3) refreshOnline()
        SyncJobService.schedule(this)
    }

    override fun onPause() {
        super.onPause()
        SyncEngine.listener = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("targa", etTarga.text.toString())
        outState.putString("zona", zona)
        outState.putString("uscita", dataUscita?.toString())
        outState.putInt("filtro", filtroElenco)
        outState.putString("photo", photo?.absolutePath)
        outState.putString("pendingPhoto", pendingPhoto)
        outState.putInt("tab", tab)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != 0) showTab(0) else super.onBackPressed()
    }

    /** Elimina scatti temporanei rimasti da sessioni precedenti. */
    private fun cleanupTemp() {
        FilesProvider.tmpDir(this).listFiles()?.forEach { it.delete() }
    }

    // ------------------------------------------------------------------ helper grafici

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(fill: Int, radius: Int = 12, stroke: Int = 0, strokeW: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radius).toFloat()
        if (strokeW > 0) g.setStroke(dp(strokeW), stroke)
        return g
    }

    private fun ripple(d: Drawable, dark: Boolean): Drawable =
        RippleDrawable(ColorStateList.valueOf(if (dark) 0x40FFFFFF else 0x30000000), d, null)

    private fun button(text: String, fill: Int, fg: Int = Color.WHITE, stroke: Int = 0, height: Int = 60, size: Float = 18f): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(fg)
        b.textSize = size
        b.typeface = Typeface.DEFAULT_BOLD
        b.stateListAnimator = null
        b.background = ripple(shape(fill, 12, stroke, if (stroke != 0) 2 else 0), fill != Color.WHITE)
        b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)).apply { topMargin = dp(8) }
        return b
    }

    private fun text(s: String, size: Float = 16f, color: Int = Color.BLACK, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun section(title: String): TextView {
        val t = text(title, 15f, GREEN_DARK, true)
        t.letterSpacing = 0.05f
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
        return t
    }

    private fun card(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.background = shape(Color.WHITE, 14, 0xFFDDE3DF.toInt(), 1)
        l.setPadding(dp(14), dp(12), dp(14), dp(14))
        l.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        return l
    }

    /** Solo lettere e numeri, convertiti in maiuscolo. */
    private val plateFilter = InputFilter { source, start, end, _, _, _ ->
        val sb = StringBuilder()
        for (i in start until end) {
            val c = source[i].uppercaseChar()
            if (c in 'A'..'Z' || c in '0'..'9') sb.append(c)
        }
        if (sb.toString() == source.subSequence(start, end).toString()) null else sb.toString()
    }

    private fun plateEdit(hint: String, size: Float): EditText {
        val e = EditText(this)
        e.hint = hint
        e.textSize = size
        e.setSingleLine(true)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        e.filters = arrayOf(plateFilter, InputFilter.LengthFilter(10))
        e.setTextColor(Color.BLACK)
        e.setHintTextColor(0xFF9E9E9E.toInt())
        e.background = shape(Color.WHITE, 10, GREEN, 2)
        e.setPadding(dp(14), dp(10), dp(14), dp(10))
        return e
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ------------------------------------------------------------------ costruzione UI

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)

        // Intestazione
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(GREEN)
        header.setPadding(dp(16), dp(12), dp(16), dp(12))
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(text("Censimento Mezzi", 22f, Color.WHITE, true))
        titles.addView(text("Impresa Sangalli · piazzale aziendale", 13f, 0xFFD7EEDD.toInt()))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvHeaderCount = text("", 14f, Color.WHITE, true)
        tvHeaderCount.gravity = Gravity.CENTER
        tvHeaderCount.background = shape(GREEN_DARK, 18)
        tvHeaderCount.setPadding(dp(12), dp(6), dp(12), dp(6))
        header.addView(tvHeaderCount)
        root.addView(header)

        // Barra delle sezioni
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setBackgroundColor(GREEN_DARK)
        listOf("CENSIMENTO", "ELENCO", "ESPORTA", "ONLINE").forEachIndexed { i, label ->
            val b = Button(this)
            b.text = label
            b.textSize = 12.5f
            b.setPadding(dp(2), 0, dp(2), 0)
            b.minWidth = 0
            b.minimumWidth = 0
            b.typeface = Typeface.DEFAULT_BOLD
            b.stateListAnimator = null
            b.setOnClickListener { hideKeyboard(); showTab(i) }
            tabs.addView(b, LinearLayout.LayoutParams(0, dp(52), if (i == 0) 1.3f else 1f))
            tabButtons.add(b)
        }
        root.addView(tabs)

        // Stato dell'invio online
        tvSync = text("", 14f, Color.WHITE, true)
        tvSync.setPadding(dp(16), dp(7), dp(16), dp(7))
        tvSync.setOnClickListener { hideKeyboard(); showTab(3) }
        root.addView(tvSync)

        val content = FrameLayout(this)
        screens.add(buildCensimento())
        screens.add(buildElenco())
        screens.add(buildEsporta())
        screens.add(buildOnline())
        screens.forEach { content.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun showTab(i: Int) {
        tab = i
        screens.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { idx, b ->
            val sel = idx == i
            b.setTextColor(if (sel) GREEN_DARK else Color.WHITE)
            b.background = ripple(shape(if (sel) Color.WHITE else GREEN_DARK, 0), !sel)
        }
        refreshCounts()
        if (i == 1) refreshList()
        if (i == 2) refreshSummary()
        if (i == 3) refreshOnline()
    }

    private fun refreshCounts() {
        val n = db.count()
        tvHeaderCount.text = if (n == 1) "1 mezzo" else "$n mezzi"
    }

    // ------------------------------------------------------------------ sezione CENSIMENTO

    private fun buildCensimento(): View {
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(4), dp(16), dp(24))
        scroll.addView(col)

        // 1. Targa
        col.addView(section("1. TARGA DEL MEZZO *"))
        etTarga = plateEdit("es. AB123CD", 30f)
        etTarga.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        etTarga.gravity = Gravity.CENTER
        etTarga.letterSpacing = 0.1f
        etTarga.imeOptions = EditorInfo.IME_ACTION_DONE
        etTarga.setOnEditorActionListener { _, _, _ -> hideKeyboard(); etTarga.clearFocus(); true }
        etTarga.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        etTarga.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { checkDuplicate(); tvErr.visibility = View.GONE }
        })
        col.addView(etTarga)
        tvDup = text("", 14f, ORANGE, true)
        tvDup.visibility = View.GONE
        tvDup.setPadding(dp(4), dp(6), dp(4), 0)
        col.addView(tvDup)
        btnDupUscita = button("", BLUE, height = 52, size = 15f)
        btnDupUscita.visibility = View.GONE
        btnDupUscita.setOnClickListener {
            hideKeyboard()
            db.byTarga(currentTarga()).firstOrNull()?.let { editUscita(it) }
        }
        col.addView(btnDupUscita)

        // 2. Zona
        col.addView(section("2. ZONA DI PARCHEGGIO *"))
        for (r in 0 until 3) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until 3) {
                val z = ZONE[r * 3 + c]
                val b = Button(this)
                b.text = z
                b.textSize = 24f
                b.typeface = Typeface.DEFAULT_BOLD
                b.stateListAnimator = null
                b.setOnClickListener { zona = z; updateZoneButtons(); tvErr.visibility = View.GONE; hideKeyboard() }
                row.addView(b, LinearLayout.LayoutParams(0, dp(62), 1f).apply {
                    leftMargin = if (c == 0) 0 else dp(5); rightMargin = if (c == 2) 0 else dp(5)
                })
                zoneButtons[z] = b
            }
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }

        // 3. Fotografia
        col.addView(section("3. FOTOGRAFIA *"))
        val hint = text("Inquadrare il mezzo con la targa ben leggibile.", 14f, GREY)
        hint.setPadding(0, dp(4), 0, 0)
        col.addView(hint)
        ivPreview = ImageView(this)
        ivPreview.scaleType = ImageView.ScaleType.FIT_CENTER
        ivPreview.adjustViewBounds = true
        ivPreview.background = shape(0xFF263238.toInt(), 12)
        ivPreview.setPadding(dp(2), dp(2), dp(2), dp(2))
        ivPreview.visibility = View.GONE
        ivPreview.setOnClickListener { onScatta() }
        col.addView(ivPreview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)).apply { topMargin = dp(8) })
        btnFoto = button("SCATTA FOTO", GREEN_DARK, height = 64)
        btnFoto.setOnClickListener { hideKeyboard(); onScatta() }
        col.addView(btnFoto)

        // 4. Data di uscita (facoltativa)
        col.addView(section("4. DATA DI USCITA (facoltativa)"))
        col.addView(text("Da compilare quando il mezzo lascia il piazzale. Si può inserire anche in seguito dalla scheda ELENCO.", 14f, GREY).apply { setPadding(0, dp(4), 0, 0) })
        val ur = LinearLayout(this)
        ur.orientation = LinearLayout.HORIZONTAL
        btnUscita = button("IMPOSTA DATA DI USCITA", Color.WHITE, GREEN_DARK, GREEN_DARK, height = 56, size = 16f)
        btnUscita.layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
        btnUscita.setOnClickListener {
            hideKeyboard()
            pickUscita(dataUscita, LocalDate.now(), null) { d -> dataUscita = d; updateUscitaButton(); tvErr.visibility = View.GONE }
        }
        ur.addView(btnUscita)
        btnUscitaClear = button("✕", Color.WHITE, RED, RED, height = 56, size = 18f)
        btnUscitaClear.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(8) }
        btnUscitaClear.setOnClickListener { dataUscita = null; updateUscitaButton() }
        ur.addView(btnUscitaClear)
        col.addView(ur, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // Data/ora automatiche + salvataggio
        val auto = text("Data e ora del censimento vengono registrate automaticamente al salvataggio.", 13f, GREY)
        auto.setPadding(0, dp(16), 0, 0)
        col.addView(auto)
        tvErr = text("", 16f, RED, true)
        tvErr.visibility = View.GONE
        tvErr.setPadding(dp(12), dp(10), dp(12), dp(10))
        tvErr.background = shape(0xFFFFEBEE.toInt(), 10)
        tvErr.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        col.addView(tvErr)
        btnSalva = button("SALVA CENSIMENTO", GREEN, height = 72, size = 21f)
        (btnSalva.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        btnSalva.setOnClickListener { hideKeyboard(); onSalva() }
        col.addView(btnSalva)
        tvLast = text("", 14f, GREEN_DARK)
        tvLast.visibility = View.GONE
        tvLast.setPadding(dp(12), dp(10), dp(12), dp(10))
        tvLast.background = shape(GREEN_LIGHT, 10)
        tvLast.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        col.addView(tvLast)
        return scroll
    }

    private fun updateZoneButtons() {
        zoneButtons.forEach { (z, b) ->
            val sel = z == zona
            b.setTextColor(if (sel) Color.WHITE else GREEN_DARK)
            b.background = ripple(shape(if (sel) GREEN else Color.WHITE, 12, GREEN, 2), sel)
        }
    }

    private fun updateUscitaButton() {
        val d = dataUscita
        if (d == null) {
            btnUscita.text = "IMPOSTA DATA DI USCITA"
            btnUscita.setTextColor(GREEN_DARK)
            btnUscita.background = ripple(shape(Color.WHITE, 12, GREEN_DARK, 2), false)
            btnUscitaClear.visibility = View.GONE
        } else {
            btnUscita.text = "USCITA: ${d.format(D_FMT)}  ·  MODIFICA"
            btnUscita.setTextColor(Color.WHITE)
            btnUscita.background = ripple(shape(BLUE, 12), true)
            btnUscitaClear.visibility = View.VISIBLE
        }
    }

    /** Calendario per scegliere la data di uscita (non precedente a [min]). */
    private fun pickUscita(initial: LocalDate?, min: LocalDate, onClear: (() -> Unit)?, onPick: (LocalDate) -> Unit) {
        var d = initial ?: LocalDate.now()
        if (d.isBefore(min)) d = min
        val dlg = DatePickerDialog(this, { _, y, m, day -> onPick(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth)
        dlg.datePicker.minDate = min.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        dlg.setTitle("Data di uscita dal piazzale")
        if (onClear != null) dlg.setButton(DialogInterface.BUTTON_NEUTRAL, "RIMUOVI") { _, _ -> onClear() }
        dlg.show()
    }

    private fun currentTarga(): String = etTarga.text.toString().trim().uppercase()

    private fun checkDuplicate() {
        val t = currentTarga()
        val prev = if (t.length >= 3) db.byTarga(t) else emptyList()
        if (prev.isEmpty()) {
            tvDup.visibility = View.GONE
            btnDupUscita.visibility = View.GONE
        } else {
            val last = prev.first()
            val volte = if (prev.size == 1) "1 volta" else "${prev.size} volte"
            tvDup.text = "Attenzione: targa già censita $volte (ultima: zona ${last.zona}, ${last.data} ${last.ora.take(5)}).\n" +
                "La nuova foto non sovrascrive le precedenti: verrà salvata con suffisso progressivo." +
                (if (last.dataUscita != null) "\nUscita registrata il ${last.dataUscita}." else "")
            tvDup.visibility = View.VISIBLE
            btnDupUscita.text = if (last.dataUscita == null) "REGISTRA USCITA DI QUESTA TARGA" else "MODIFICA DATA DI USCITA (${last.dataUscita})"
            btnDupUscita.visibility = View.VISIBLE
        }
    }

    private fun updatePreview() {
        val f = photo
        if (f == null || !f.exists()) {
            ivPreview.setImageDrawable(null)
            ivPreview.visibility = View.GONE
            btnFoto.text = "SCATTA FOTO"
            btnFoto.background = ripple(shape(GREEN_DARK, 12), true)
            btnFoto.setTextColor(Color.WHITE)
        } else {
            ivPreview.setImageBitmap(loadBitmap({ f.inputStream() }, 1280))
            ivPreview.visibility = View.VISIBLE
            btnFoto.text = "FOTO ACQUISITA ✓  ·  RIFAI FOTO"
            btnFoto.background = ripple(shape(Color.WHITE, 12, GREEN_DARK, 2), false)
            btnFoto.setTextColor(GREEN_DARK)
        }
    }

    // ------------------------------------------------------------------ fotocamera

    private fun onScatta() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_PERM_CAMERA)
            return
        }
        launchCamera()
    }

    private fun launchCamera() {
        val f = File(FilesProvider.tmpDir(this), "scatto_${System.currentTimeMillis()}.jpg")
        val uri = FilesProvider.uriFor(FilesProvider.ROOT_TMP, f.name)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        i.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("foto", uri)
        pendingPhoto = f.absolutePath
        try {
            startActivityForResult(i, REQ_CAMERA)
        } catch (e: ActivityNotFoundException) {
            pendingPhoto = null
            alert("Fotocamera non disponibile", "Sul telefono non è presente un'app fotocamera utilizzabile.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAMERA) return
        val p = pendingPhoto ?: return
        pendingPhoto = null
        val f = File(p)
        if (resultCode == RESULT_OK && f.exists() && f.length() > 0) {
            photo?.takeIf { it.absolutePath != f.absolutePath }?.delete()
            photo = f
            tvErr.visibility = View.GONE
            updatePreview()
        } else {
            f.delete()
            if (resultCode == RESULT_OK) toast("La fotografia non è stata acquisita, riprovare.")
        }
    }

    // ------------------------------------------------------------------ permessi

    private fun hasStoragePermission(): Boolean =
        !PhotoStore.legacy || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Su Android 8-9 esegue [action] solo dopo aver ottenuto il permesso di scrittura. */
    private fun withStorage(action: () -> Unit) {
        if (hasStoragePermission()) { action(); return }
        pendingStorageAction = action
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), REQ_PERM_STORAGE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_PERM_CAMERA -> if (granted) launchCamera() else permissionDenied(
                Manifest.permission.CAMERA,
                "Per censire i mezzi è necessario consentire l'uso della fotocamera."
            )
            REQ_PERM_STORAGE -> {
                val action = pendingStorageAction
                pendingStorageAction = null
                if (granted) {
                    PhotoStore.ensureFolder()
                    action?.invoke()
                } else permissionDenied(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    "Per salvare foto ed esportazioni nella memoria del telefono è necessario consentire l'accesso ai file."
                )
            }
        }
    }

    private fun permissionDenied(perm: String, msg: String) {
        val b = AlertDialog.Builder(this).setTitle("Autorizzazione necessaria").setMessage(msg)
        if (!shouldShowRequestPermissionRationale(perm)) {
            b.setMessage("$msg\n\nL'autorizzazione è stata negata: attivarla da Impostazioni › App › Censimento Mezzi › Autorizzazioni.")
            b.setPositiveButton("APRI IMPOSTAZIONI") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            b.setNegativeButton("ANNULLA", null)
        } else {
            b.setPositiveButton("OK", null)
        }
        b.show()
    }

    // ------------------------------------------------------------------ salvataggio

    private fun onSalva() {
        if (saving) return
        val targa = currentTarga()
        val errors = ArrayList<String>()
        if (targa.isEmpty()) errors.add("• Inserire la targa")
        else if (targa.length < 3) errors.add("• Targa troppo corta")
        if (zona == null) errors.add("• Selezionare la zona di parcheggio")
        dataUscita?.let { if (it.isBefore(LocalDate.now())) errors.add("• La data di uscita non può essere precedente alla data del censimento") }
        val f = photo
        if (f == null || !f.exists() || f.length() == 0L) errors.add("• Scattare la fotografia del mezzo")
        if (errors.isNotEmpty()) {
            tvErr.text = "Impossibile salvare:\n" + errors.joinToString("\n")
            tvErr.visibility = View.VISIBLE
            return
        }
        tvErr.visibility = View.GONE
        val uscita = dataUscita
        withStorage { doSave(targa, zona!!, f!!, uscita) }
    }

    private fun doSave(targa: String, z: String, f: File, uscita: LocalDate?) {
        saving = true
        btnSalva.isEnabled = false
        btnSalva.text = "SALVATAGGIO…"
        Thread {
            try {
                val now = LocalDateTime.now()
                val saved = PhotoStore.save(this, db, targa, f)
                db.insert(Censimento(0, targa, z, now.format(D_FMT), now.format(T_FMT), saved.name, saved.uri, System.currentTimeMillis(),
                    dataUscita = uscita?.format(D_FMT)))
                f.delete()
                runOnUiThread {
                    saving = false
                    btnSalva.isEnabled = true
                    btnSalva.text = "SALVA CENSIMENTO"
                    photo = null
                    dataUscita = null
                    updateUscitaButton()
                    etTarga.setText("")
                    updatePreview()
                    refreshCounts()
                    SyncJobService.schedule(this)
                    refreshSyncStatus()
                    tvLast.text = "✓ Ultimo salvato: $targa · zona $z · ${now.format(T_FMT)}\nFoto: ${saved.name}"
                    tvLast.visibility = View.VISIBLE
                    toast("Censimento salvato: ${saved.name}")
                    // la zona resta selezionata per velocizzare il censimento della stessa area
                    (screens[0] as ScrollView).smoothScrollTo(0, 0)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    saving = false
                    btnSalva.isEnabled = true
                    btnSalva.text = "SALVA CENSIMENTO"
                    alert("Errore nel salvataggio", e.message ?: e.toString())
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ sezione ELENCO

    private inner class CensAdapter : BaseAdapter() {
        var items: List<Censimento> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val c = items[position]
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(16), dp(12), dp(16), dp(12))
            val top = LinearLayout(this@MainActivity)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            val t = text(c.targa, 20f, Color.BLACK, true)
            t.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            top.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val z = text("Zona ${c.zona}", 15f, Color.WHITE, true)
            z.background = shape(GREEN, 14)
            z.setPadding(dp(10), dp(3), dp(10), dp(3))
            top.addView(z)
            row.addView(top)
            row.addView(text("${c.data}  ${c.ora}   ·   ${c.remoteName ?: c.nomeFile}", 14f, GREY))
            if (c.dataUscita != null) row.addView(text("↗ Uscito il ${c.dataUscita}", 14f, BLUE, true))
            else row.addView(text("● Nel piazzale", 14f, GREEN, true))
            if (Config(this@MainActivity).ready()) {
                val (label, color) = when (c.syncState) {
                    SyncState.SENT -> "✓ Inviato a SharePoint" to GREEN
                    SyncState.FAILED -> "⚠ Non inviato: ${c.syncError ?: "errore"}" to RED
                    SyncState.EXCLUDED -> "Non inviato (registrato prima dell'attivazione)" to GREY
                    else -> "⏳ In attesa di invio" to ORANGE
                }
                row.addView(text(label, 13f, color, true))
            }
            return row
        }
    }

    private fun buildElenco(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        top.setPadding(dp(16), dp(12), dp(16), dp(8))
        etSearch = plateEdit("Cerca targa…", 18f)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshList() }
        })
        top.addView(etSearch)
        val fr = LinearLayout(this)
        fr.orientation = LinearLayout.HORIZONTAL
        listOf("TUTTI", "NEL PIAZZALE", "USCITI").forEachIndexed { i, label ->
            val b = Button(this)
            b.text = label
            b.textSize = 13f
            b.typeface = Typeface.DEFAULT_BOLD
            b.stateListAnimator = null
            b.minWidth = 0; b.minimumWidth = 0
            b.setPadding(dp(4), 0, dp(4), 0)
            b.setOnClickListener { filtroElenco = i; updateFilterButtons(); refreshList() }
            fr.addView(b, LinearLayout.LayoutParams(0, dp(44), if (i == 1) 1.3f else 1f).apply {
                leftMargin = if (i == 0) 0 else dp(4); rightMargin = if (i == 2) 0 else dp(4)
            })
            filterButtons.add(b)
        }
        top.addView(fr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        tvListCount = text("", 14f, GREY)
        tvListCount.setPadding(dp(4), dp(8), 0, 0)
        top.addView(tvListCount)
        col.addView(top)

        val lv = ListView(this)
        listAdapter = CensAdapter()
        lv.adapter = listAdapter
        lv.setBackgroundColor(Color.WHITE)
        lv.divider = shape(0xFFE0E0E0.toInt(), 0)
        lv.dividerHeight = dp(1)
        lv.setOnItemClickListener { _, _, pos, _ -> showDetail(listAdapter.items[pos]) }
        val empty = text("Nessun censimento registrato.", 16f, GREY)
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(16), dp(40), dp(16), dp(16))
        val frame = FrameLayout(this)
        frame.addView(lv)
        frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        lv.emptyView = empty
        col.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun updateFilterButtons() {
        filterButtons.forEachIndexed { i, b ->
            val sel = i == filtroElenco
            b.setTextColor(if (sel) Color.WHITE else GREEN_DARK)
            b.background = ripple(shape(if (sel) GREEN else Color.WHITE, 10, GREEN, 2), sel)
        }
    }

    private fun refreshList() {
        val q = etSearch.text.toString().trim().uppercase()
        val all = db.allNewestFirst()
        val list = all.filter {
            (q.isEmpty() || it.targa.contains(q)) && when (filtroElenco) {
                1 -> it.dataUscita == null
                2 -> it.dataUscita != null
                else -> true
            }
        }
        listAdapter.items = list
        listAdapter.notifyDataSetChanged()
        val presenti = all.count { it.dataUscita == null }
        tvListCount.text = if (q.isEmpty() && filtroElenco == 0)
            "Totale: ${all.size}  ·  nel piazzale: $presenti  ·  usciti: ${all.size - presenti}\nTocca una riga per la foto e la data di uscita"
        else "Trovati: ${list.size} su ${all.size}"
    }

    private fun showDetail(c: Censimento) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        var info = "Zona ${c.zona}\nCensito il ${c.data} ore ${c.ora}\nFile: ${c.nomeFile}\n" +
            (if (c.dataUscita != null) "Data di uscita: ${c.dataUscita}" else "Data di uscita: non inserita (mezzo nel piazzale)")
        if (c.remoteName != null && c.remoteName != c.nomeFile) info += "\nNome su SharePoint: ${c.remoteName}"
        c.syncError?.let { info += "\nNota invio: $it" }
        box.addView(text(info, 16f, Color.BLACK))
        val iv = ImageView(this)
        iv.adjustViewBounds = true
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        val bmp = loadBitmap({ PhotoStore.open(this, c.uri) }, 1280)
        if (bmp != null) {
            iv.setImageBitmap(bmp)
            box.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(12) })
        } else {
            box.addView(text("Fotografia non trovata sul telefono (forse eliminata dalla galleria).", 14f, RED))
        }
        AlertDialog.Builder(this)
            .setTitle(c.targa)
            .setView(box)
            .setPositiveButton("CHIUDI", null)
            .setNeutralButton("ELIMINA") { _, _ -> confirmDelete(c) }
            .setNegativeButton(if (c.dataUscita == null) "DATA USCITA" else "MODIFICA USCITA") { _, _ -> editUscita(c) }
            .show()
    }

    private fun editUscita(c: Censimento) {
        val min = try { LocalDate.parse(c.data, D_FMT) } catch (e: Exception) { LocalDate.now() }
        val current = c.dataUscita?.let { try { LocalDate.parse(it, D_FMT) } catch (e: Exception) { null } }
        val onClear: (() -> Unit)? = if (c.dataUscita == null) null else ({
            db.setUscita(c.id, null); refreshList(); refreshSummary(); checkDuplicate(); toast("Data di uscita rimossa: ${c.targa} risulta nel piazzale")
        })
        pickUscita(current, min, onClear) { d ->
            db.setUscita(c.id, d.format(D_FMT))
            refreshList(); refreshSummary(); checkDuplicate()
            toast("${c.targa}: uscita il ${d.format(D_FMT)}")
        }
    }

    private fun confirmDelete(c: Censimento) {
        AlertDialog.Builder(this)
            .setTitle("Eliminare il censimento?")
            .setMessage("Targa ${c.targa} – zona ${c.zona} – ${c.data} ${c.ora}\n\nVerranno eliminati il censimento e la fotografia ${c.nomeFile} dal telefono." +
                if (c.syncState == SyncState.SENT || c.syncState == SyncState.PHOTO_SENT) "\n\nI dati già inviati a SharePoint NON vengono modificati." else "")
            .setPositiveButton("ELIMINA") { _, _ ->
                PhotoStore.delete(this, c.uri)
                db.delete(c.id)
                refreshList()
                refreshCounts()
                toast("Censimento eliminato")
            }
            .setNegativeButton("ANNULLA", null)
            .show()
    }

    // ------------------------------------------------------------------ sezione ESPORTA

    private fun buildEsporta(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(24))
        scroll.addView(col)

        val title = text("ESPORTA CENSIMENTO COMPLETO", 21f, GREEN_DARK, true)
        col.addView(title)

        val sum = card()
        tvSummary = text("", 16f, Color.BLACK)
        sum.addView(tvSummary)
        sum.addView(text("Mezzi nel piazzale per zona", 14f, GREY, true).apply { setPadding(0, dp(12), 0, dp(4)) })
        for (r in 0 until 3) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until 3) {
                val z = ZONE[r * 3 + c]
                val cell = text("", 16f, GREEN_DARK, true)
                cell.gravity = Gravity.CENTER
                cell.background = shape(GREEN_LIGHT, 8)
                cell.setPadding(0, dp(8), 0, dp(8))
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = if (c == 0) 0 else dp(4); rightMargin = if (c == 2) 0 else dp(4); topMargin = dp(6)
                })
                zoneCountViews[z] = cell
            }
            sum.addView(row)
        }
        col.addView(sum)

        val xl = card()
        xl.addView(text("File Excel (.xlsx)", 17f, Color.BLACK, true))
        xl.addView(text("Colonne: Targa · Zona parcheggio · Data censimento · Ora censimento · Nome file fotografia · Data uscita", 14f, GREY))
        val bx = button("ESPORTA EXCEL", GREEN, height = 64)
        bx.setOnClickListener { startExport(false) }
        xl.addView(bx)
        col.addView(xl)

        val zp = card()
        zp.addView(text("Archivio ZIP (facoltativo)", 17f, Color.BLACK, true))
        zp.addView(text("Contiene il file Excel e tutte le fotografie (cartella \"foto\"). Utile per inviare il censimento completo in un unico file.", 14f, GREY))
        val bz = button("ESPORTA ZIP (EXCEL + FOTO)", GREEN_DARK, height = 64)
        bz.setOnClickListener { startExport(true) }
        zp.addView(bz)
        col.addView(zp)

        val info = text(
            "Dove si trovano i file sul telefono:\n" +
                "• Esportazioni: ${Exporter.folderDescription()}\n" +
                "• Fotografie: ${PhotoStore.folderDescription()}\n" +
                "Dopo l'esportazione è possibile condividere il file (e-mail, WhatsApp, Drive…).",
            14f, GREY
        )
        info.setPadding(dp(4), dp(16), dp(4), dp(8))
        col.addView(info)

        val clear = button("AZZERA ARCHIVIO", Color.WHITE, RED, RED, height = 52, size = 15f)
        (clear.layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
        clear.setOnClickListener { confirmClear() }
        col.addView(clear)
        col.addView(text("Svuota l'elenco dei censimenti nell'app per iniziare un nuovo censimento. Le fotografie restano nella cartella del telefono. Esportare prima il censimento.", 13f, GREY).apply { setPadding(dp(4), dp(6), dp(4), 0) })
        return scroll
    }

    private fun refreshSummary() {
        val all = db.allChronological()
        if (all.isEmpty()) {
            tvSummary.text = "Censimenti registrati: 0"
        } else {
            val first = all.first()
            val last = all.last()
            val targhe = all.map { it.targa }.toSet().size
            tvSummary.text = "Censimenti registrati: ${all.size}\nTarghe diverse: $targhe\n" +
                "Nel piazzale: ${all.count { it.dataUscita == null }}  ·  Usciti: ${all.count { it.dataUscita != null }}\n" +
                "Primo: ${first.data} ${first.ora.take(5)}\nUltimo: ${last.data} ${last.ora.take(5)}"
        }
        val counts = all.filter { it.dataUscita == null }.groupingBy { it.zona }.eachCount()
        ZONE.forEach { z -> zoneCountViews[z]?.text = "$z: ${counts[z] ?: 0}" }
    }

    private fun startExport(zip: Boolean) {
        if (db.count() == 0) {
            alert("Nessun dato", "Non ci sono censimenti da esportare.")
            return
        }
        withStorage { runExport(zip) }
    }

    private fun runExport(zip: Boolean) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(20), dp(24), dp(8))
        val msg = text(if (zip) "Creazione archivio ZIP…" else "Creazione file Excel…", 16f)
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pb.isIndeterminate = !zip
        pb.progressTintList = ColorStateList.valueOf(GREEN)
        box.addView(msg)
        box.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val dlg = AlertDialog.Builder(this).setTitle("Esportazione").setView(box).setCancelable(false).show()

        Thread {
            try {
                val res = if (zip) Exporter.exportZip(this, db) { i, n ->
                    runOnUiThread { pb.max = n; pb.progress = i; msg.text = "Aggiunta fotografie: $i di $n" }
                } else Exporter.exportXlsx(this, db)
                runOnUiThread { dlg.dismiss(); showExportResult(res) }
            } catch (e: Exception) {
                runOnUiThread { dlg.dismiss(); alert("Errore nell'esportazione", e.message ?: e.toString()) }
            }
        }.start()
    }

    private fun showExportResult(r: Exporter.Result) {
        val sb = StringBuilder()
        sb.append("File: ${r.name}\n")
        sb.append("Salvato in: ${Exporter.folderDescription()}\n\n")
        sb.append("Censimenti esportati: ${r.rows}")
        if (r.mime == Exporter.MIME_ZIP) {
            sb.append("\nFotografie incluse: ${r.photos}")
            if (r.missing.isNotEmpty()) {
                sb.append("\n\nAttenzione: ${r.missing.size} fotografie non trovate sul telefono:\n")
                sb.append(r.missing.take(10).joinToString(", "))
                if (r.missing.size > 10) sb.append("…")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Esportazione completata")
            .setMessage(sb.toString())
            .setPositiveButton("CONDIVIDI") { _, _ -> share(r) }
            .setNegativeButton("CHIUDI", null)
            .show()
    }

    private fun share(r: Exporter.Result) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = r.mime
        i.putExtra(Intent.EXTRA_STREAM, r.uri)
        i.putExtra(Intent.EXTRA_SUBJECT, "Censimento mezzi piazzale – ${r.name}")
        i.clipData = ClipData.newRawUri(r.name, r.uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(i, "Condividi censimento"))
        } catch (e: ActivityNotFoundException) {
            toast("Nessuna app disponibile per la condivisione")
        }
    }

    private fun confirmClear() {
        val n = db.count()
        if (n == 0) { toast("L'archivio è già vuoto"); return }
        val sc = db.syncCounts()
        val unsent = (sc[SyncState.PENDING] ?: 0) + (sc[SyncState.PHOTO_SENT] ?: 0) + (sc[SyncState.FAILED] ?: 0)
        val warn = if (Config(this).ready() && unsent > 0) "\n\nATTENZIONE: $unsent censimenti NON sono ancora stati inviati a SharePoint e andrebbero persi." else ""
        AlertDialog.Builder(this)
            .setTitle("Azzerare l'archivio?")
            .setMessage("Verranno eliminati dall'elenco $n censimenti.\n\nLe fotografie NON vengono cancellate e restano in ${PhotoStore.folderDescription()}.$warn\n\nHai già esportato il censimento completo?")
            .setPositiveButton("AZZERA") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Conferma definitiva")
                    .setMessage("L'operazione non può essere annullata. Procedere?")
                    .setPositiveButton("SÌ, AZZERA") { _, _ ->
                        db.clear(); refreshSummary(); refreshCounts(); refreshSyncStatus(); toast("Archivio azzerato")
                    }
                    .setNegativeButton("ANNULLA", null)
                    .show()
            }
            .setNegativeButton("ANNULLA", null)
            .show()
    }

    // ------------------------------------------------------------------ sezione ONLINE (SharePoint)

    private fun timeLabel(ms: Long): String {
        if (ms <= 0) return "mai"
        val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault())
        return if (dt.toLocalDate() == java.time.LocalDate.now()) "oggi alle ${dt.format(T_FMT).take(5)}"
        else "${dt.format(D_FMT)} ${dt.format(T_FMT).take(5)}"
    }

    /** Barra colorata sotto le schede, visibile in tutte le sezioni. */
    private fun refreshSyncStatus() {
        if (!::tvSync.isInitialized) return
        val cfg = Config(this)
        val sc = db.syncCounts()
        val pending = (sc[SyncState.PENDING] ?: 0) + (sc[SyncState.PHOTO_SENT] ?: 0)
        val failed = sc[SyncState.FAILED] ?: 0
        val (msg, color) = when {
            !cfg.ready() -> "Invio online non configurato · tocca qui" to 0xFF757575.toInt()
            !Auth.signedIn(this) -> (if (cfg.authExpired) "⚠ Accesso Microsoft scaduto · tocca per accedere"
                else "⚠ Accedere con l'account Microsoft · tocca qui") + (if (pending > 0) " ($pending in attesa)" else "") to RED
            SyncEngine.running && pending > 0 -> "⟳ Invio a SharePoint in corso… ($pending da inviare)" to 0xFF1565C0.toInt()
            pending > 0 -> "⏳ $pending in attesa di invio" + (cfg.lastError?.let { " · $it" } ?: "") to ORANGE
            failed > 0 -> "⚠ $failed censimenti non inviati · tocca per dettagli" to RED
            else -> "✓ Tutto inviato a SharePoint · ultimo invio ${timeLabel(cfg.lastOk)}" to GREEN
        }
        tvSync.text = msg
        tvSync.setBackgroundColor(color)
        tvSync.maxLines = 2
        tvSync.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun field(label: String, hint: String, parent: LinearLayout): EditText {
        parent.addView(text(label, 13f, GREY, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        val e = EditText(this)
        e.hint = hint
        e.textSize = 15f
        e.setSingleLine(true)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
        e.setTextColor(Color.BLACK)
        e.setHintTextColor(0xFF9E9E9E.toInt())
        e.background = shape(Color.WHITE, 8, 0xFFBDBDBD.toInt(), 1)
        e.setPadding(dp(10), dp(8), dp(10), dp(8))
        parent.addView(e)
        return e
    }

    private fun buildOnline(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(24))
        scroll.addView(col)

        col.addView(text("INVIO AUTOMATICO A SHAREPOINT", 21f, GREEN_DARK, true))
        col.addView(text("Ogni censimento salvato viene inviato automaticamente: la foto nella cartella \"foto\" e una nuova riga nel file Excel condiviso. Senza connessione i censimenti restano in coda e partono appena torna la rete.", 14f, GREY).apply { setPadding(0, dp(6), 0, 0) })

        val st = card()
        tvOnlineStatus = text("", 16f, Color.BLACK)
        st.addView(tvOnlineStatus)
        btnLogin = button("ACCEDI CON ACCOUNT MICROSOFT", GREEN, height = 60)
        btnLogin.setOnClickListener { onLoginButton() }
        st.addView(btnLogin)
        btnSyncNow = button("INVIA ORA", GREEN_DARK, height = 56, size = 16f)
        btnSyncNow.setOnClickListener {
            Config(this).lastError = null
            SyncJobService.schedule(this)
            toast("Invio avviato")
            refreshOnline()
        }
        st.addView(btnSyncNow)
        btnRetry = button("RIPROVA I CENSIMENTI IN ERRORE", Color.WHITE, RED, RED, height = 52, size = 15f)
        btnRetry.setOnClickListener {
            db.retryFailed(); SyncJobService.schedule(this); refreshOnline(); refreshSyncStatus()
        }
        st.addView(btnRetry)
        col.addView(st)

        btnCfgToggle = button("CONFIGURAZIONE (AMMINISTRATORE) ▸", Color.WHITE, GREY, 0xFFBDBDBD.toInt(), height = 50, size = 14f)
        (btnCfgToggle.layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        btnCfgToggle.setOnClickListener { toggleConfig() }
        col.addView(btnCfgToggle)

        cfgBox = card()
        cfgBox.visibility = View.GONE
        cfgBox.addView(text("Dati forniti dall'ufficio IT dopo la registrazione dell'app in Microsoft Entra ID.", 13f, GREY))
        val redirect = text("URI di reindirizzamento da registrare:\n${Auth.REDIRECT_URI}", 13f, GREEN_DARK, true)
        redirect.setPadding(dp(10), dp(8), dp(10), dp(8))
        redirect.background = shape(GREEN_LIGHT, 8)
        redirect.setTextIsSelectable(true)
        redirect.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        cfgBox.addView(redirect)
        etTenant = field("ID tenant o dominio", "es. impresasangalli.it", cfgBox)
        etClient = field("ID applicazione (client)", "es. 1a2b3c4d-....", cfgBox)
        etSite = field("Indirizzo del sito SharePoint", "https://azienda.sharepoint.com/sites/NomeSito", cfgBox)
        etFolder = field("Cartella nella raccolta Documenti", Config.DEFAULT_FOLDER, cfgBox)
        etExcel = field("Nome del file Excel", Config.DEFAULT_EXCEL, cfgBox)
        val bSave = button("SALVA CONFIGURAZIONE", GREEN, height = 56, size = 16f)
        (bSave.layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
        bSave.setOnClickListener { saveConfig() }
        cfgBox.addView(bSave)
        val bTest = button("PROVA CONNESSIONE", Color.WHITE, GREEN_DARK, GREEN_DARK, height = 52, size = 15f)
        bTest.setOnClickListener { testConnection() }
        cfgBox.addView(bTest)
        col.addView(cfgBox)
        return scroll
    }

    private fun toggleConfig() {
        val show = cfgBox.visibility != View.VISIBLE
        if (show) {
            val cfg = Config(this)
            etTenant.setText(cfg.tenant); etClient.setText(cfg.clientId); etSite.setText(cfg.siteUrl)
            etFolder.setText(cfg.folder); etExcel.setText(cfg.excelName)
        }
        cfgBox.visibility = if (show) View.VISIBLE else View.GONE
        btnCfgToggle.text = if (show) "CONFIGURAZIONE (AMMINISTRATORE) ▾" else "CONFIGURAZIONE (AMMINISTRATORE) ▸"
    }

    private fun refreshOnline() {
        if (!::tvOnlineStatus.isInitialized) return
        val cfg = Config(this)
        val sc = db.syncCounts()
        val sent = sc[SyncState.SENT] ?: 0
        val pending = (sc[SyncState.PENDING] ?: 0) + (sc[SyncState.PHOTO_SENT] ?: 0)
        val failed = sc[SyncState.FAILED] ?: 0
        val signed = Auth.signedIn(this)
        val sb = StringBuilder()
        if (!cfg.ready()) {
            sb.append("Invio online non ancora configurato.\nAprire \"Configurazione (amministratore)\" e inserire i dati forniti dall'IT.")
        } else {
            sb.append("Account: ").append(if (signed) (cfg.account ?: "collegato") else if (cfg.authExpired) "accesso scaduto" else "non collegato")
            sb.append("\nDestinazione: ").append(cfg.siteUrl.removePrefix("https://")).append(" › Documenti")
            if (cfg.folder.isNotEmpty()) sb.append(" › ").append(cfg.folder)
            sb.append("\n\nInviati: $sent\nIn attesa: $pending")
            if (failed > 0) sb.append("\nIn errore: $failed")
            sb.append("\nUltimo invio riuscito: ${timeLabel(cfg.lastOk)}")
            if (SyncEngine.running) sb.append("\n\n⟳ Invio in corso…")
            else cfg.lastError?.let { if (pending > 0 || failed > 0 || !signed) sb.append("\n\nUltimo problema: $it") }
        }
        tvOnlineStatus.text = sb.toString()
        btnLogin.visibility = if (cfg.ready()) View.VISIBLE else View.GONE
        if (signed) {
            btnLogin.text = "ESCI DALL'ACCOUNT"
            btnLogin.setTextColor(GREEN_DARK)
            btnLogin.background = ripple(shape(Color.WHITE, 12, GREEN_DARK, 2), false)
        } else {
            btnLogin.text = "ACCEDI CON ACCOUNT MICROSOFT"
            btnLogin.setTextColor(Color.WHITE)
            btnLogin.background = ripple(shape(GREEN, 12), true)
        }
        btnSyncNow.visibility = if (cfg.ready() && signed && pending > 0) View.VISIBLE else View.GONE
        btnRetry.visibility = if (failed > 0) View.VISIBLE else View.GONE
    }

    private fun onLoginButton() {
        if (!Auth.signedIn(this)) {
            try {
                startActivity(Auth.loginIntent(this))
            } catch (e: ActivityNotFoundException) {
                alert("Browser non disponibile", "Per accedere serve un browser installato sul telefono (es. Chrome).")
            }
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Uscire dall'account?")
            .setMessage("I nuovi censimenti resteranno sul telefono e non verranno inviati finché non si accede di nuovo.")
            .setPositiveButton("ESCI") { _, _ -> Auth.signOut(this); refreshOnline(); refreshSyncStatus() }
            .setNegativeButton("ANNULLA", null)
            .show()
    }

    private fun handleAuthRedirect(i: Intent?) {
        val uri = i?.data
        if (!Auth.isRedirect(uri)) return
        i?.data = null
        showTab(3)
        toast("Completamento accesso…")
        Thread {
            try {
                val who = Auth.completeLogin(this, uri!!)
                runOnUiThread {
                    toast("Accesso effettuato: $who")
                    refreshOnline(); refreshSyncStatus()
                    askBacklogThenSync()
                }
            } catch (e: Exception) {
                runOnUiThread { alert("Accesso non riuscito", e.message ?: e.toString()); refreshOnline() }
            }
        }.start()
    }

    /** Al primo accesso chiede se inviare anche i censimenti già presenti sul telefono. */
    private fun askBacklogThenSync() {
        val cfg = Config(this)
        val pending = db.syncCounts()[SyncState.PENDING] ?: 0
        if (cfg.backlogAsked || pending == 0) {
            cfg.backlogAsked = true
            SyncJobService.schedule(this)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Censimenti già presenti")
            .setMessage("Sul telefono ci sono $pending censimenti registrati prima dell'attivazione dell'invio online.\n\nInviarli anche a SharePoint?")
            .setCancelable(false)
            .setPositiveButton("SÌ, INVIA TUTTO") { _, _ -> cfg.backlogAsked = true; SyncJobService.schedule(this); refreshOnline() }
            .setNegativeButton("NO, SOLO I NUOVI") { _, _ ->
                cfg.backlogAsked = true; db.excludePending(); refreshOnline(); refreshSyncStatus()
                if (tab == 1) refreshList()
            }
            .show()
    }

    private fun saveConfig() {
        val tenant = etTenant.text.toString().trim()
        val client = etClient.text.toString().trim()
        val site = etSite.text.toString().trim().trimEnd('/')
        val folder = etFolder.text.toString().trim().trim('/')
        var excel = etExcel.text.toString().trim()
        val errors = ArrayList<String>()
        if (tenant.isEmpty()) errors.add("• ID tenant o dominio")
        if (!Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(client)) errors.add("• ID applicazione (formato xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)")
        if (!site.startsWith("https://") || !site.contains(".sharepoint.com")) errors.add("• Indirizzo sito (https://….sharepoint.com/sites/…)")
        if (excel.isEmpty()) excel = Config.DEFAULT_EXCEL
        if (!excel.endsWith(".xlsx", true)) excel += ".xlsx"
        if (errors.isNotEmpty()) {
            alert("Dati non validi", "Controllare:\n" + errors.joinToString("\n"))
            return
        }
        val cfg = Config(this)
        val identityChanged = tenant != cfg.tenant || client != cfg.clientId
        val targetChanged = identityChanged || site != cfg.siteUrl || folder != cfg.folder || excel != cfg.excelName
        cfg.tenant = tenant; cfg.clientId = client; cfg.siteUrl = site; cfg.folder = folder; cfg.excelName = excel
        if (targetChanged) cfg.clearRemoteCache()
        if (identityChanged) Auth.signOut(this)
        cfg.lastError = null
        hideKeyboard()
        toggleConfig()
        refreshOnline(); refreshSyncStatus()
        toast("Configurazione salvata")
        if (!Auth.signedIn(this)) onLoginButton() else SyncJobService.schedule(this)
    }

    private fun testConnection() {
        val cfg = Config(this)
        if (!cfg.ready()) { alert("Configurazione incompleta", "Salvare prima la configurazione."); return }
        if (!Auth.signedIn(this)) { alert("Accesso necessario", "Accedere prima con l'account Microsoft."); return }
        toast("Verifica in corso…")
        Thread {
            val msg = try {
                val g = Graph({ force -> Auth.accessToken(this, force) })
                val siteId = g.resolveSite(cfg.siteUrl)
                val drive = g.driveId(siteId)
                cfg.driveId = drive
                val ra = RemoteArchive(g, drive, cfg.folder, cfg.excelName, Config.TABLE, null)
                val folderOk = ra.folderExists()
                val excelOk = g.item(drive, if (cfg.folder.isEmpty()) cfg.excelName else "${cfg.folder}/${cfg.excelName}") != null
                "Connessione riuscita ✓\n\nSito SharePoint raggiungibile.\nCartella \"${cfg.folder}\": " +
                    (if (folderOk) "presente" else "verrà creata al primo invio") +
                    "\nFile Excel: " + (if (excelOk) "presente" else "verrà creato al primo invio")
            } catch (e: Exception) {
                "Connessione non riuscita:\n\n${e.message ?: e}"
            }
            runOnUiThread { alert("Prova connessione", msg); refreshOnline(); refreshSyncStatus() }
        }.start()
    }

    // ------------------------------------------------------------------ utilità

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun alert(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    /** Carica un'anteprima ridotta, ruotata secondo l'orientamento EXIF. */
    private fun loadBitmap(open: () -> InputStream?, maxPx: Int): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            (open() ?: return null).use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1
            while (o.outWidth / (s * 2) >= maxPx || o.outHeight / (s * 2) >= maxPx) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val bmp = (open() ?: return null).use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
            val orient = try {
                open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val deg = when (orient) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg == 0f) bmp else {
                val m = Matrix(); m.postRotate(deg)
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
        } catch (e: Throwable) {
            null
        }
    }
}
