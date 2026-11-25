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
    private lateinit var tvServer: TextView
    private lateinit var btnSyncOra: Button
    private lateinit var btnFallite: Button
    private lateinit var btnCollega: Button
    private lateinit var btnIndirizzo: Button
    // dati facoltativi del modulo di censimento
    private lateinit var btnAltri: Button
    private lateinit var altriBox: LinearLayout
    private lateinit var etMarca: EditText
    private lateinit var etModello: EditText
    private lateinit var etColore: EditText
    private lateinit var spTipo: android.widget.Spinner
    private lateinit var etPratica: EditText
    private lateinit var etEnte: EditText
    private lateinit var etProvv: android.widget.AutoCompleteTextView
    private lateinit var etNote: EditText
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val syncListener: () -> Unit = {
        uiHandler.post {
            refreshSyncStatus()
            if (tab == 3) refreshServer()
            // al termine della sincronizzazione l'elenco mostra i dati ricevuti dal sito
            if (!SyncEngine.running) { refreshCounts(); if (tab == 1) refreshList(); if (tab == 2) refreshSummary(); if (tab == 0) checkDuplicate() }
        }
    }
    private val zoneCountViews = HashMap<String, TextView>()
    private lateinit var tvUpdate: TextView
    private lateinit var btnUpdate: Button
    private val updateListener: () -> Unit = { uiHandler.post { refreshUpdate() } }

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

        Updater.justUpdated(this)?.let { toast("App aggiornata alla versione $it") }

        // Android 8-9: permesso di scrittura per creare Pictures/CensimentoMezzi
        if (PhotoStore.legacy) {
            if (hasStoragePermission()) PhotoStore.ensureFolder()
            else if (savedInstanceState == null) requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), REQ_PERM_STORAGE
            )
        }
    }

    override fun onResume() {
        super.onResume()
        SyncEngine.listener = syncListener
        refreshSyncStatus()
        if (tab == 3) refreshServer()
        SyncJobService.schedule(this)
        Updater.foreground = true
        Updater.listener = updateListener
        UpdateJobService.schedule(this)
        maybeUpdate(false)
    }

    override fun onPause() {
        super.onPause()
        SyncEngine.listener = null
        Updater.foreground = false
        Updater.listener = null
        Updater.setFormBusy(this, formBusy())
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
        listOf("CENSIMENTO", "ELENCO", "ESPORTA", "SITO").forEachIndexed { i, label ->
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
        screens.add(buildServer())
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
        if (i == 3) { refreshServer(); refreshUpdate() }
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
        // zone dal sito (ultime ricevute), tre per riga
        for (riga in ServerConfig(this).zone.chunked(3)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until 3) {
                val z = riga.getOrNull(c)
                if (z == null) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(62), 1f)); continue }
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

        // 4. Altri dati (facoltativi)
        col.addView(section("4. ALTRI DATI (facoltativi)"))
        btnAltri = button("MOSTRA ALTRI DATI ▸", Color.WHITE, GREY, 0xFFBDBDBD.toInt(), height = 50, size = 15f)
        btnAltri.setOnClickListener {
            val vis = altriBox.visibility != View.VISIBLE
            altriBox.visibility = if (vis) View.VISIBLE else View.GONE
            btnAltri.text = if (vis) "NASCONDI ALTRI DATI ▾" else "MOSTRA ALTRI DATI ▸"
        }
        col.addView(btnAltri)
        altriBox = LinearLayout(this)
        altriBox.orientation = LinearLayout.VERTICAL
        altriBox.visibility = View.GONE
        etMarca = campo("Marca", "es. Fiat", altriBox)
        etModello = campo("Modello", "es. Panda", altriBox)
        etColore = campo("Colore", "es. Bianco", altriBox)
        spTipo = spinnerTipi(null, altriBox)
        etPratica = campo("N. pratica / verbale", "", altriBox)
        etEnte = campo("Ente", "es. Prefettura di Milano", altriBox)
        etProvv = campoProvvedimento(null, altriBox)
        etNote = campo("Note", "danni, chiavi, documenti a bordo…", altriBox, multilinea = true)
        col.addView(altriBox)

        // 5. Data di uscita (facoltativa)
        col.addView(section("5. DATA DI USCITA (facoltativa)"))
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

    private fun testoCampo(e: EditText): String? = e.text.toString().trim().ifEmpty { null }
    private fun tipoScelto(sp: android.widget.Spinner): Pair<Int, String>? = ServerConfig(this).tipi().getOrNull(sp.selectedItemPosition - 1)
    private fun altriCompilati(): Boolean = ::etMarca.isInitialized &&
        listOf(etMarca, etModello, etColore, etPratica, etEnte, etProvv, etNote).any { it.text.isNotBlank() } || (::spTipo.isInitialized && spTipo.selectedItemPosition > 0)

    private fun doSave(targa: String, z: String, f: File, uscita: LocalDate?) {
        saving = true
        btnSalva.isEnabled = false
        btnSalva.text = "SALVATAGGIO…"
        val tipo = tipoScelto(spTipo)
        val base = Censimento(0, targa, z, "", "", "", "", 0, dataUscita = uscita?.format(D_FMT), uuid = java.util.UUID.randomUUID().toString(),
            marca = testoCampo(etMarca), modello = testoCampo(etModello), colore = testoCampo(etColore), tipoId = tipo?.first, tipoNome = tipo?.second,
            pratica = testoCampo(etPratica), ente = testoCampo(etEnte), provvedimento = testoCampo(etProvv), note = testoCampo(etNote))
        Thread {
            try {
                val now = LocalDateTime.now()
                val saved = PhotoStore.save(this, db, targa, f)
                db.insert(base.copy(data = now.format(D_FMT), ora = now.format(T_FMT), nomeFile = saved.name, uri = saved.uri, ts = System.currentTimeMillis()))
                // invio al sito: entrata e foto restano in coda finché il sito non è raggiungibile
                db.accoda(TipoOp.INGRESSO, base.uuid)
                db.accoda(TipoOp.FOTO, base.uuid, org.json.JSONObject().put("foto_uuid", java.util.UUID.randomUUID().toString()))
                f.delete()
                runOnUiThread {
                    saving = false
                    btnSalva.isEnabled = true
                    btnSalva.text = "SALVA CENSIMENTO"
                    photo = null
                    dataUscita = null
                    updateUscitaButton()
                    etTarga.setText("")
                    listOf(etMarca, etModello, etColore, etPratica, etEnte, etProvv, etNote).forEach { it.setText("") }
                    spTipo.setSelection(0)
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
            val veicolo = listOfNotNull(c.marca, c.modello, c.colore).joinToString(" ")
            row.addView(text("${c.data}  ${c.ora.take(5)}" + (if (veicolo.isNotEmpty()) "   ·   $veicolo" else ""), 14f, GREY))
            if (c.dataUscita != null) row.addView(text("↗ Uscito il ${c.dataUscita}", 14f, BLUE, true))
            else row.addView(text("● Nel piazzale", 14f, GREEN, true))
            val stato = statoInvio(c)
            if (stato != null) row.addView(text(stato.first, 13f, stato.second, true))
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

    /** Stato dell'invio al sito di un censimento: testo e colore, oppure null se non c'è nulla da segnalare. */
    private fun statoInvio(c: Censimento): Pair<String, Int>? {
        val err = db.erroreDi(c.uuid)
        return when {
            db.haOperazioni(c.uuid) && db.operazioniFallite().any { it.uuid == c.uuid } -> "⚠ Rifiutato dal sito: ${err ?: "errore"}" to RED
            db.haOperazioni(c.uuid) -> "⏳ Da inviare al sito" to ORANGE
            !c.sulServer -> "Solo su questo telefono" to GREY
            else -> null
        }
    }

    private fun showDetail(c0: Censimento) {
        val c = db.get(c0.id) ?: c0
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val righe = mutableListOf("Zona ${c.zona}", "Entrato il ${c.data} ore ${c.ora.take(5)}",
            if (c.dataUscita != null) "Uscito il ${c.dataUscita}" else "Nel piazzale (nessuna data di uscita)")
        listOfNotNull(c.marca, c.modello, c.colore).joinToString(" ").takeIf { it.isNotEmpty() }?.let { righe.add("Veicolo: $it") }
        c.tipoNome?.let { righe.add("Tipo: $it") }
        c.pratica?.let { righe.add("Pratica / verbale: $it") }
        c.ente?.let { righe.add("Ente: $it") }
        c.provvedimento?.let { righe.add("Provvedimento: $it") }
        c.note?.let { righe.add("Note: $it") }
        statoInvio(c)?.let { righe.add(it.first) }
        box.addView(text(righe.joinToString("\n"), 16f, Color.BLACK))
        val iv = ImageView(this)
        iv.adjustViewBounds = true
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(12) }
        val locale = if (c.uri.isNotEmpty()) loadBitmap({ PhotoStore.open(this, c.uri) }, 1280) else null
        when {
            locale != null -> { iv.setImageBitmap(locale); box.addView(iv, lp) }
            c.fotoIds.isNotEmpty() && ServerConfig(this).collegato() -> {
                val attesa = text("Caricamento della foto dal sito…", 14f, GREY)
                box.addView(attesa); box.addView(iv, lp)
                Thread {
                    val bmp = try { val f = Server(this).scaricaFoto(c.fotoIds.first(), 1024); loadBitmap({ f.inputStream() }, 1280) } catch (e: Exception) { null }
                    runOnUiThread { if (bmp != null) { iv.setImageBitmap(bmp); attesa.visibility = View.GONE } else attesa.text = "Foto non disponibile (sito non raggiungibile)" }
                }.start()
            }
            c.fotoIds.isNotEmpty() -> box.addView(text("La foto è sul sito: collegare il telefono per vederla.", 14f, GREY))
            else -> box.addView(text(if (c.uri.isNotEmpty()) "Fotografia non trovata sul telefono (forse eliminata dalla galleria)." else "Nessuna foto.", 14f, RED))
        }
        AlertDialog.Builder(this)
            .setTitle(c.targa)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("CHIUDI", null)
            .setNeutralButton("ALTRO…") { _, _ -> altreAzioni(c) }
            .setNegativeButton(if (c.dataUscita == null) "DATA USCITA" else "MODIFICA USCITA") { _, _ -> editUscita(c) }
            .show()
    }

    private fun altreAzioni(c: Censimento) {
        val voci = mutableListOf("Sposta in un'altra zona", "Correggi i dati")
        val eliminabile = !c.sulServer
        if (eliminabile) voci.add("Elimina dal telefono")
        AlertDialog.Builder(this).setTitle(c.targa).setItems(voci.toTypedArray()) { _, i ->
            when (i) { 0 -> spostaZona(c); 1 -> correggi(c); 2 -> confirmDelete(c) }
        }.setNegativeButton("ANNULLA", null).show()
    }

    private fun spostaZona(c: Censimento) {
        val zone = ServerConfig(this).zone
        AlertDialog.Builder(this).setTitle("${c.targa}: nuova zona").setSingleChoiceItems(zone.toTypedArray(), zone.indexOf(c.zona)) { d, i ->
            d.dismiss()
            val z = zone[i]
            if (z == c.zona) return@setSingleChoiceItems
            val v = android.content.ContentValues(); v.put("zona", z)
            db.aggiorna(c.id, v)
            db.accoda(TipoOp.ZONA, c.uuid, org.json.JSONObject().put("zona", z))
            datiCambiati(); toast("${c.targa} spostato in zona $z")
        }.setNegativeButton("ANNULLA", null).show()
    }

    private fun correggi(c: Censimento) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(4), dp(20), dp(8))
        box.addView(text("Targa", 13f, GREY, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        val eT = plateEdit("Targa", 20f); eT.setText(c.targa); box.addView(eT)
        val eMa = campo("Marca", "", box, c.marca); val eMo = campo("Modello", "", box, c.modello); val eCo = campo("Colore", "", box, c.colore)
        val sT = spinnerTipi(c.tipoId, box)
        val ePr = campo("N. pratica / verbale", "", box, c.pratica); val eEn = campo("Ente", "", box, c.ente)
        val ePv = campoProvvedimento(c.provvedimento, box); val eNo = campo("Note", "", box, c.note, multilinea = true)
        AlertDialog.Builder(this).setTitle("Correggi ${c.targa}").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("SALVA") { _, _ ->
                val nuovaTarga = eT.text.toString().trim().uppercase()
                if (nuovaTarga.length < 3) { alert("Targa non valida", "La targa deve avere almeno 3 caratteri."); return@setPositiveButton }
                val tipo = tipoScelto(sT)
                val v = android.content.ContentValues(); val j = org.json.JSONObject()
                fun cambia(col: String, chiave: String, vecchio: Any?, nuovo: Any?) {
                    if (vecchio == nuovo) return
                    if (nuovo == null) v.putNull(col) else v.put(col, nuovo.toString())
                    j.put(chiave, nuovo ?: org.json.JSONObject.NULL)
                }
                cambia("targa", "targa", c.targa, nuovaTarga)
                cambia("marca", "marca", c.marca, testoCampo(eMa)); cambia("modello", "modello", c.modello, testoCampo(eMo))
                cambia("colore", "colore", c.colore, testoCampo(eCo))
                cambia("pratica", "pratica_numero", c.pratica, testoCampo(ePr)); cambia("ente", "ente", c.ente, testoCampo(eEn))
                cambia("provvedimento", "provvedimento", c.provvedimento, testoCampo(ePv)); cambia("note", "note", c.note, testoCampo(eNo))
                if (c.tipoId != tipo?.first) {
                    if (tipo == null) { v.putNull("tipo_id"); v.putNull("tipo_nome") } else { v.put("tipo_id", tipo.first); v.put("tipo_nome", tipo.second) }
                    j.put("tipo_veicolo_id", tipo?.first ?: org.json.JSONObject.NULL)
                }
                if (j.length() == 0) { toast("Nessuna modifica"); return@setPositiveButton }
                db.aggiorna(c.id, v)
                db.accoda(TipoOp.CORREZIONE, c.uuid, j)
                datiCambiati(); toast("Dati di ${nuovaTarga} corretti")
            }.setNegativeButton("ANNULLA", null).show()
    }

    /** Dopo una modifica locale: aggiorna le viste e avvia l'invio al sito. */
    private fun datiCambiati() {
        refreshList(); refreshSummary(); refreshCounts(); checkDuplicate()
        SyncJobService.schedule(this)
        refreshSyncStatus()
    }

    private fun editUscita(c: Censimento) {
        val min = try { LocalDate.parse(c.data, D_FMT) } catch (e: Exception) { LocalDate.now() }
        val current = c.dataUscita?.let { try { LocalDate.parse(it, D_FMT) } catch (e: Exception) { null } }
        val onClear: (() -> Unit)? = if (c.dataUscita == null) null else ({
            db.setUscita(c.id, null)
            db.accoda(TipoOp.USCITA, c.uuid, org.json.JSONObject().put("data_uscita", org.json.JSONObject.NULL))
            datiCambiati(); toast("Data di uscita rimossa: ${c.targa} risulta nel piazzale")
        })
        pickUscita(current, min, onClear) { d ->
            db.setUscita(c.id, d.format(D_FMT))
            db.accoda(TipoOp.USCITA, c.uuid, org.json.JSONObject().put("data_uscita", d.toString()))
            datiCambiati()
            toast("${c.targa}: uscita il ${d.format(D_FMT)}")
        }
    }

    private fun confirmDelete(c: Censimento) {
        AlertDialog.Builder(this)
            .setTitle("Eliminare il censimento?")
            .setMessage("Targa ${c.targa} – zona ${c.zona} – ${c.data} ${c.ora}\n\nIl censimento non è ancora sul sito: verranno eliminati dal telefono il censimento" +
                (if (c.nomeFile.isNotEmpty()) " e la fotografia ${c.nomeFile}" else "") + ", e non verrà inviato.")
            .setPositiveButton("ELIMINA") { _, _ ->
                if (c.uri.isNotEmpty()) PhotoStore.delete(this, c.uri)
                db.delete(c.id)
                refreshSyncStatus()
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
        for (riga in ServerConfig(this).zone.chunked(3)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until 3) {
                val z = riga.getOrNull(c)
                if (z == null) { row.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); continue }
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
        col.addView(text("Svuota la copia del piazzale su questo telefono. Con il telefono collegato i dati restano sul sito e vengono riscaricati alla sincronizzazione successiva. Le fotografie restano nella cartella del telefono.", 13f, GREY).apply { setPadding(dp(4), dp(6), dp(4), 0) })
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
        zoneCountViews.forEach { (z, v) -> v.text = "$z: ${counts[z] ?: 0}" }
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
        val nonInviati = db.allNewestFirst().count { !it.sulServer }
        val warn = if (nonInviati > 0) "\n\nATTENZIONE: $nonInviati censimenti NON sono ancora sul sito e andrebbero persi." else ""
        AlertDialog.Builder(this)
            .setTitle("Azzerare l'archivio?")
            .setMessage("Verranno eliminati dal telefono $n censimenti. Quelli già sul sito restano sul sito.\n\nLe fotografie NON vengono cancellate e restano in ${PhotoStore.folderDescription()}.$warn\n\nHai già esportato il censimento completo?")
            .setPositiveButton("AZZERA") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Conferma definitiva")
                    .setMessage("L'operazione non può essere annullata. Procedere?")
                    .setPositiveButton("SÌ, AZZERA") { _, _ ->
                        db.clear(); ServerConfig(this).cursore = null
                        refreshSummary(); refreshCounts(); refreshSyncStatus(); toast("Archivio azzerato")
                    }
                    .setNegativeButton("ANNULLA", null)
                    .show()
            }
            .setNegativeButton("ANNULLA", null)
            .show()
    }

    // ------------------------------------------------------------------ sezione SITO (collegamento al sito Censimento Mezzi)

    private fun timeLabel(ms: Long): String {
        if (ms <= 0) return "mai"
        val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault())
        return if (dt.toLocalDate() == java.time.LocalDate.now()) "oggi alle ${dt.format(T_FMT).take(5)}"
        else "${dt.format(D_FMT)} ${dt.format(T_FMT).take(5)}"
    }

    /** Barra colorata sotto le schede, visibile in tutte le sezioni. */
    private fun refreshSyncStatus() {
        if (!::tvSync.isInitialized) return
        val cfg = ServerConfig(this)
        val coda = db.inCoda()
        val ko = db.fallite()
        val err = cfg.ultimoErrore
        val (msg, color) = when {
            !cfg.collegato() -> (err ?: "Telefono non collegato al sito · tocca qui") + (if (coda > 0) " ($coda in attesa)" else "") to (if (err != null) RED else 0xFF757575.toInt())
            SyncEngine.running -> "⟳ Sincronizzazione con il sito…" + (if (coda > 0) " ($coda da inviare)" else "") to BLUE
            ko > 0 -> "⚠ $ko operazioni rifiutate dal sito · tocca per dettagli" to RED
            coda > 0 -> "⏳ $coda in attesa di invio" + (err?.let { " · $it" } ?: "") to ORANGE
            err != null -> "⚠ $err" to ORANGE
            cfg.ultimaSync == 0L -> "Collegato al sito · prima sincronizzazione in corso" to BLUE
            else -> "✓ Aggiornato con il sito · ${timeLabel(cfg.ultimaSync)}" to GREEN
        }
        tvSync.text = msg
        tvSync.setBackgroundColor(color)
        tvSync.maxLines = 2
        tvSync.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /* ---------- campi dei moduli ---------- */
    private fun stileCampo(e: EditText, hint: String) {
        e.hint = hint
        e.textSize = 15f
        e.setTextColor(Color.BLACK)
        e.setHintTextColor(0xFF9E9E9E.toInt())
        e.background = shape(Color.WHITE, 8, 0xFFBDBDBD.toInt(), 1)
        e.setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun etichetta(label: String, parent: LinearLayout) =
        parent.addView(text(label, 13f, GREY, true).apply { setPadding(0, dp(10), 0, dp(4)) })

    private fun campo(label: String, hint: String, parent: LinearLayout, valore: String? = null, multilinea: Boolean = false): EditText {
        etichetta(label, parent)
        val e = EditText(this)
        stileCampo(e, hint)
        if (multilinea) { e.minLines = 2; e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        else { e.setSingleLine(true); e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        valore?.let { e.setText(it) }
        parent.addView(e)
        return e
    }

    private fun campoProvvedimento(valore: String?, parent: LinearLayout): android.widget.AutoCompleteTextView {
        etichetta("Provvedimento", parent)
        val e = android.widget.AutoCompleteTextView(this)
        stileCampo(e, "es. Sequestro")
        e.setSingleLine(true)
        e.threshold = 0
        e.setAdapter(android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, ServerConfig(this).provvedimenti))
        e.setOnFocusChangeListener { _, f -> if (f) e.showDropDown() }
        valore?.let { e.setText(it) }
        parent.addView(e)
        return e
    }

    private fun spinnerTipi(scelto: Int?, parent: LinearLayout): android.widget.Spinner {
        etichetta("Tipo di veicolo", parent)
        val tipi = ServerConfig(this).tipi()
        val sp = android.widget.Spinner(this)
        sp.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("—") + tipi.map { it.second })
        sp.background = shape(Color.WHITE, 8, 0xFFBDBDBD.toInt(), 1)
        sp.setSelection(tipi.indexOfFirst { it.first == scelto } + 1)
        parent.addView(sp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        return sp
    }

    /* ---------- scheda SITO ---------- */
    private fun buildServer(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(24))
        scroll.addView(col)

        col.addView(text("SITO CENSIMENTO MEZZI", 21f, GREEN_DARK, true))
        col.addView(text("Il telefono invia entrate, uscite e modifiche al sito dell'ufficio e riceve l'elenco aggiornato del piazzale, " +
            "compresi i mezzi registrati dagli altri telefoni. Funziona sul Wi-Fi aziendale: fuori rete le operazioni restano in coda e partono appena possibile.", 14f, GREY)
            .apply { setPadding(0, dp(6), 0, 0) })

        val st = card()
        tvServer = text("", 16f, Color.BLACK)
        st.addView(tvServer)
        btnSyncOra = button("SINCRONIZZA ORA", GREEN, height = 60)
        btnSyncOra.setOnClickListener { sincronizzaOra() }
        st.addView(btnSyncOra)
        btnFallite = button("OPERAZIONI RIFIUTATE DAL SITO", Color.WHITE, RED, RED, height = 52, size = 15f)
        btnFallite.setOnClickListener { mostraFallite() }
        st.addView(btnFallite)
        col.addView(st)

        col.addView(section("AMMINISTRATORE (protetto da PIN)"))
        val adm = card()
        adm.addView(text("Collegamento del telefono con il codice generato dal sito (Controlli › Telefoni › Nuovo telefono).", 13f, GREY))
        btnCollega = button("COLLEGA AL SITO", GREEN_DARK, height = 56, size = 16f)
        btnCollega.setOnClickListener { conPin { if (ServerConfig(this).collegato()) scollega() else collega() } }
        adm.addView(btnCollega)
        btnIndirizzo = button("INDIRIZZO DEL SITO", Color.WHITE, GREY, 0xFFBDBDBD.toInt(), height = 48, size = 14f)
        btnIndirizzo.setOnClickListener { conPin { cambiaIndirizzo() } }
        adm.addView(btnIndirizzo)
        col.addView(adm)

        // Aggiornamenti dell'app
        col.addView(section("AGGIORNAMENTI DELL'APP"))
        val up = card()
        tvUpdate = text("", 15f, Color.BLACK)
        up.addView(tvUpdate)
        btnUpdate = button("CONTROLLA AGGIORNAMENTI", Color.WHITE, GREEN_DARK, GREEN_DARK, height = 52, size = 15f)
        btnUpdate.setOnClickListener { maybeUpdate(true) }
        up.addView(btnUpdate)
        col.addView(up)
        return scroll
    }

    private fun refreshServer() {
        if (!::tvServer.isInitialized) return
        val cfg = ServerConfig(this)
        val sb = StringBuilder()
        if (!cfg.collegato()) {
            sb.append("Telefono NON collegato al sito.\n")
            sb.append(if (cfg.scollegato) "Per collegarlo serve un nuovo codice dal sito." else "L'amministratore deve collegarlo con il codice generato dal sito.")
            cfg.ultimoErrore?.let { sb.append("\n\n$it") }
        } else {
            sb.append("Telefono: ").append(cfg.nomeTelefono ?: "—")
            sb.append("\nSito: ").append(cfg.url.removePrefix("https://"))
            sb.append("\nUltima sincronizzazione: ").append(timeLabel(cfg.ultimaSync))
            sb.append("\nIn attesa di invio: ").append(db.inCoda())
            val ko = db.fallite()
            if (ko > 0) sb.append("\nRifiutate dal sito: $ko")
            if (SyncEngine.running) sb.append("\n\n⟳ Sincronizzazione in corso…")
            else cfg.ultimoErrore?.let { sb.append("\n\nUltimo problema: $it") }
        }
        val locali = db.inCoda()
        if (!cfg.collegato() && locali > 0) sb.append("\n\nOperazioni in attesa sul telefono: $locali")
        tvServer.text = sb.toString()
        btnSyncOra.visibility = if (cfg.collegato()) View.VISIBLE else View.GONE
        btnSyncOra.isEnabled = !SyncEngine.running
        btnFallite.visibility = if (db.fallite() > 0) View.VISIBLE else View.GONE
        btnCollega.text = if (cfg.collegato()) "SCOLLEGA DAL SITO" else "COLLEGA AL SITO"
    }

    private fun sincronizzaOra() {
        if (SyncEngine.running) return
        toast("Sincronizzazione in corso…")
        Thread {
            val ok = SyncEngine.run(this)
            runOnUiThread {
                refreshServer(); refreshSyncStatus()
                val cfg = ServerConfig(this)
                toast(if (ok && cfg.ultimoErrore == null) "Sincronizzazione completata" else cfg.ultimoErrore ?: "Sincronizzazione non riuscita")
            }
        }.start()
    }

    /** Chiede il PIN dell'amministratore (al primo uso lo fa impostare), poi esegue [azione]. */
    private fun conPin(azione: () -> Unit) {
        val cfg = ServerConfig(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(4), dp(20), dp(8))
        fun pinEdit(hint: String) = EditText(this).also {
            stileCampo(it, hint)
            it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            it.filters = arrayOf(InputFilter.LengthFilter(8))
            box.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        if (!cfg.pinImpostato()) {
            box.addView(text("Scegli un PIN amministratore (4-8 cifre). Servirà per collegare o scollegare il telefono: non comunicarlo agli operatori.", 14f, GREY))
            val p1 = pinEdit("Nuovo PIN"); val p2 = pinEdit("Ripeti il PIN")
            AlertDialog.Builder(this).setTitle("PIN amministratore").setView(box)
                .setPositiveButton("SALVA") { _, _ ->
                    val a = p1.text.toString(); val b = p2.text.toString()
                    when {
                        a.length < 4 -> alert("PIN non valido", "Il PIN deve avere almeno 4 cifre.")
                        a != b -> alert("PIN non valido", "I due PIN non coincidono.")
                        else -> { cfg.impostaPin(a); toast("PIN impostato"); azione() }
                    }
                }.setNegativeButton("ANNULLA", null).show()
        } else {
            val p = pinEdit("PIN amministratore")
            AlertDialog.Builder(this).setTitle("PIN amministratore").setView(box)
                .setPositiveButton("OK") { _, _ ->
                    if (cfg.verificaPin(p.text.toString())) azione() else alert("PIN errato", "Il PIN non è corretto.")
                }.setNegativeButton("ANNULLA", null).show()
        }
    }

    private fun collega() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(4), dp(20), dp(8))
        box.addView(text("Sul sito: Controlli › Telefoni › Nuovo telefono. Inserisci qui il codice mostrato (vale 24 ore, una sola volta).", 14f, GREY))
        val eNome = campo("Nome del telefono", "es. Samsung piazzale 1", box, "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        etichetta("Codice di attivazione", box)
        val eCod = EditText(this)
        stileCampo(eCod, "es. K7P2-QX9M")
        eCod.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        eCod.textSize = 22f
        eCod.typeface = Typeface.MONOSPACE
        box.addView(eCod)
        AlertDialog.Builder(this).setTitle("Collega al sito").setView(box)
            .setPositiveButton("COLLEGA") { _, _ ->
                val codice = eCod.text.toString().trim(); val nome = eNome.text.toString().trim().ifEmpty { Build.MODEL }
                if (codice.length < 6) { alert("Codice non valido", "Inserire il codice di attivazione mostrato dal sito."); return@setPositiveButton }
                toast("Collegamento in corso…")
                Thread {
                    try {
                        val n = Server(this).attiva(codice, nome)
                        val daInviare = db.accodaNonInviati()
                        SyncEngine.run(this)
                        runOnUiThread {
                            SyncJobService.schedule(this)
                            refreshServer(); refreshSyncStatus(); refreshCounts()
                            alert("Telefono collegato", "Telefono «$n» collegato al sito." +
                                (if (daInviare > 0) "\n\n$daInviare censimenti già presenti sul telefono verranno inviati al sito." else ""))
                        }
                    } catch (e: Exception) {
                        runOnUiThread { alert("Collegamento non riuscito", Server.messaggio(e)) }
                    }
                }.start()
            }.setNegativeButton("ANNULLA", null).show()
    }

    private fun scollega() {
        val coda = db.inCoda()
        AlertDialog.Builder(this).setTitle("Scollegare il telefono dal sito?")
            .setMessage("Il telefono smetterà di inviare e ricevere dati. Per ricollegarlo servirà un nuovo codice dal sito." +
                (if (coda > 0) "\n\nATTENZIONE: $coda operazioni non ancora inviate resteranno sul telefono e partiranno solo dopo il nuovo collegamento." else ""))
            .setPositiveButton("SCOLLEGA") { _, _ ->
                ServerConfig(this).scollega(); SyncJobService.annulla(this)
                refreshServer(); refreshSyncStatus(); toast("Telefono scollegato")
            }.setNegativeButton("ANNULLA", null).show()
    }

    private fun cambiaIndirizzo() {
        val cfg = ServerConfig(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(4), dp(20), dp(8))
        val e = campo("Indirizzo del sito", ServerConfig.URL_PREDEFINITO, box, cfg.url)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        AlertDialog.Builder(this).setTitle("Indirizzo del sito").setView(box)
            .setPositiveButton("SALVA") { _, _ ->
                val u = e.text.toString().trim().trimEnd('/')
                if (!u.startsWith("https://")) { alert("Indirizzo non valido", "L'indirizzo deve iniziare con https://"); return@setPositiveButton }
                cfg.url = u; cfg.cursore = null; refreshServer(); toast("Indirizzo salvato")
            }
            .setNeutralButton("PREDEFINITO") { _, _ -> cfg.url = ServerConfig.URL_PREDEFINITO; cfg.cursore = null; refreshServer() }
            .setNegativeButton("ANNULLA", null).show()
    }

    private fun mostraFallite() {
        val ko = db.operazioniFallite()
        if (ko.isEmpty()) { refreshServer(); return }
        val nomi = mapOf(TipoOp.INGRESSO to "Entrata", TipoOp.FOTO to "Foto", TipoOp.USCITA to "Uscita", TipoOp.ZONA to "Spostamento", TipoOp.CORREZIONE to "Correzione")
        val testo = ko.take(30).joinToString("\n\n") { o ->
            val targa = db.perUuid(o.uuid)?.targa ?: "?"
            "• ${nomi[o.tipo] ?: o.tipo} $targa: ${o.errore ?: "errore"}"
        }
        AlertDialog.Builder(this).setTitle("Operazioni rifiutate dal sito")
            .setMessage("$testo\n\nCorreggere i dati (o chiedere all'ufficio) e poi riprovare, oppure scartarle.")
            .setPositiveButton("RIPROVA") { _, _ -> db.riprovaFallite(); SyncJobService.schedule(this); refreshServer(); refreshSyncStatus() }
            .setNeutralButton("SCARTA") { _, _ ->
                AlertDialog.Builder(this).setTitle("Scartare le operazioni rifiutate?")
                    .setMessage("Non verranno più inviate al sito.")
                    .setPositiveButton("SCARTA") { _, _ -> db.scartaFallite(); refreshServer(); refreshSyncStatus(); refreshList() }
                    .setNegativeButton("ANNULLA", null).show()
            }
            .setNegativeButton("CHIUDI", null).show()
    }

    // ------------------------------------------------------------------ aggiornamento automatico

    /** Modulo di censimento con dati non salvati (l'aggiornamento chiuderebbe l'app). */
    private fun formBusy(): Boolean =
        saving || pendingPhoto != null || photo != null || dataUscita != null ||
            (::etTarga.isInitialized && etTarga.text.isNotBlank()) || altriCompilati()

    /** Controlla (se è il momento) e installa l'aggiornamento se il modulo è vuoto. */
    private fun maybeUpdate(manual: Boolean) {
        if (Updater.running) return
        if (manual) toast("Controllo aggiornamenti…")
        val due = manual || Updater.checkDue(this)
        Thread {
            val ready = if (due) Updater.check(this) else Updater.readyFile(this) != null
            runOnUiThread {
                refreshUpdate()
                if (ready) offerInstall(manual)
                else if (manual) toast(Updater.lastError(this) ?: "L'app è aggiornata (versione ${Updater.currentName(this)})")
                else askInstallPermissionOnce()
            }
        }.start()
    }

    private fun offerInstall(manual: Boolean) {
        if (!Updater.foreground || isFinishing) return
        val name = Updater.readyName(this) ?: return
        if (formBusy()) {
            if (manual) alert("Aggiornamento pronto", "La versione $name verrà installata appena il censimento in corso sarà salvato e l'app riaperta.")
            return
        }
        if (!Updater.canInstall(this)) { askInstallPermission(); return }
        if (!manual && !Updater.promptAllowed(this)) return
        Updater.setFormBusy(this, false)
        toast("Installazione della versione $name: l'app si chiuderà, poi riaprirla")
        Thread { Updater.install(this) }.start()
    }

    /** Alla prima apertura propone subito l'autorizzazione, così gli aggiornamenti futuri sono automatici. */
    private fun askInstallPermissionOnce() {
        if (Updater.canInstall(this) || Updater.permissionAsked(this) || !Updater.foreground) return
        askInstallPermission()
    }

    private fun askInstallPermission() {
        Updater.setPermissionAsked(this)
        AlertDialog.Builder(this)
            .setTitle("Aggiornamenti automatici")
            .setMessage("Per installare da sola le nuove versioni, l'app ha bisogno dell'autorizzazione \"Installa app sconosciute\".\n\n" +
                "Nella schermata che si apre attivare \"Consenti da questa fonte\", poi tornare indietro.")
            .setPositiveButton("APRI IMPOSTAZIONI") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                } catch (e: ActivityNotFoundException) {
                    alert("Impostazione non trovata", "Attivarla da Impostazioni › App › Censimento Mezzi › Installa app sconosciute.")
                }
            }
            .setNegativeButton("PIÙ TARDI", null)
            .show()
    }

    private fun refreshUpdate() {
        if (!::tvUpdate.isInitialized) return
        val sb = StringBuilder("Versione installata: ${Updater.currentName(this)}")
        sb.append("\nUltimo controllo: ${timeLabel(Updater.lastCheck(this))}")
        Updater.readyName(this)?.let { sb.append("\n\nNuova versione $it pronta: si installa all'apertura dell'app, con il modulo di censimento vuoto.") }
        if (!Updater.canInstall(this)) sb.append("\n\n⚠ Manca l'autorizzazione \"Installa app sconosciute\": gli aggiornamenti non possono installarsi.")
        if (Updater.running) sb.append("\n\n⟳ Controllo in corso…")
        else Updater.lastError(this)?.let { sb.append("\n\n$it") }
        tvUpdate.text = sb.toString()
        btnUpdate.isEnabled = !Updater.running
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
