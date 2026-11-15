package de.tvtransfer.convert

import java.io.File

/**
 * Entwurf-Konverter: zerlegt eine Android-APK und erzeugt daraus einen **Entwurf** einer Samsung-TV-App (Tizen-Web-App).
 *
 * Übernommen werden: App-Name, Symbol, Farben, Texte, Bilder und die Bildschirme (Layouts) mit ihren Elementen –
 * nachgebaut als HTML mit Fernbedienungssteuerung. NICHT übernommen werden kann die Programmlogik (Android-Code):
 * Was beim Tippen passiert, Wiedergabe, Anmeldung, Daten laden usw. muss nachprogrammiert werden.
 * Ein Bericht (bericht.html, entwurf.json) in der App listet genau auf, was fehlt.
 *
 * Geschützte Apps (Lizenz-/Kopierschutz) werden nicht verarbeitet – siehe Analyzer.
 */
object DraftGenerator {

    class Draft(val files: LinkedHashMap<String, ByteArray>, val screens: List<String>, val summary: List<String>)

    private const val MAX_SCREENS = 30

    fun generate(apk: File, analysis: Analysis): Draft {
        val names = Zip.names(apk)
        val res = Zip.entry(apk, "resources.arsc")?.let { ResourceTable.parse(it) } ?: ResourceTable.empty()
        val manifest = runCatching { BinaryXml.parse(Zip.entry(apk, "AndroidManifest.xml")!!) }.getOrDefault(emptyList())
        val out = LinkedHashMap<String, ByteArray>()
        val images = LinkedHashMap<String, String>() // APK-Pfad -> Pfad im Entwurf

        // --- Farben (Thema) aus benannten Farbressourcen
        val colors = res.entries.filter { it.type == "color" && it.dataType in 0x1c..0x1f && it.defaultConfig }
        fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { k -> colors.firstOrNull { it.name.equals(k, true) } ?: colors.firstOrNull { it.name.contains(k, true) } }
            ?.let { ResourceTable.css(it.data) }
        // Fernseher: immer dunkles, kontrastreiches Design; App-Farbe nur als Akzent, wenn gut sichtbar
        val accentEntry = listOf("colorPrimary", "primary", "colorAccent", "accent", "purple_500", "brand")
            .firstNotNullOfOrNull { k -> colors.firstOrNull { it.name.equals(k, true) } ?: colors.firstOrNull { it.name.contains(k, true) } }
        val primary = accentEntry?.data?.takeIf { luminance(it) in 0.05..0.6 }?.let { ResourceTable.css(it or 0xFF000000.toInt()) } ?: "#4DA3FF"
        val background = "#101418"
        val textColor = "#F1F5F9"

        // --- Bildschirme (Layouts)
        val layouts = res.entries.filter { it.type == "layout" && it.text?.endsWith(".xml") == true }.distinctBy { it.name }
        val primaryScreens = layouts.filter { l -> SCREEN_PREFIXES.any { l.name.startsWith(it) } }
        val otherScreens = layouts.filter { l -> l !in primaryScreens && NOT_SCREENS.none { l.name.startsWith(it) } }
        val screenLayouts = (primaryScreens + if (primaryScreens.size < 6) otherScreens else emptyList())
            .sortedWith(compareBy({ if (it.name.contains("main") || it.name.contains("home")) 0 else 1 }, { it.name }))
            .take(MAX_SCREENS)

        val ctx = Ctx(apk, res, images)
        val screens = ArrayList<Pair<String, String>>() // Titel -> HTML
        for (l in screenLayouts) {
            val xml = Zip.entry(apk, l.text!!) ?: continue
            val tree = BinaryXmlTree.parse(xml) ?: continue
            val html = StringBuilder()
            ctx.render(tree, html, 0)
            screens += prettyTitle(l.name) to html.toString()
        }
        // Bildschirme ohne sichtbaren Inhalt (nur leere Container) weglassen – außer es bliebe keiner übrig
        val visible = Regex("class=\"d-(text|btn|input|img|list|check|video|placeholder)")
        screens.filter { visible.containsMatchIn(it.second) }.takeIf { it.isNotEmpty() }?.let { screens.retainAll(it.toSet()) }

        // --- Texte, Adressen, Aktivitäten (für Nachbearbeitung)
        val strings = res.entries.filter { it.type == "string" && it.defaultConfig && it.text != null && !it.name.startsWith("abc_") && !it.name.startsWith("mtrl_") && !it.name.startsWith("m3_") && !it.name.startsWith("material_") && !it.name.startsWith("common_google") }
            .associate { it.name to it.text!! }
        val activities = manifest.filter { it.name == "activity" }.mapNotNull { it.attrs["name"] }
        val urls = names.filter { Regex("^classes\\d*\\.dex$").matches(it) }
            .flatMap { n -> Zip.entry(apk, n)?.let { Analyzer.extractUrls(it) } ?: emptyList() }
            .let { Analyzer.rankUrls(it, analysis.packageName) }

        // --- Bilder kopieren (Pixelbilder) bzw. schreiben (umgewandelte Vektorgrafiken)
        images.forEach { (src, dst) -> Zip.entry(apk, src)?.let { out[dst] = it } }
        ctx.svgs.forEach { (dst, svg) -> out[dst] = svg.toByteArray() }

        val appName = analysis.displayName
        val summary = buildList {
            add("${screens.size} Bildschirm(e) aus Android-Layouts nachgebaut")
            if (screens.isEmpty()) add("Keine Android-Layouts gefunden (z. B. Jetpack Compose oder Flutter) – Oberfläche muss neu gestaltet werden")
            add("${strings.size} Texte übernommen, ${images.size + ctx.svgs.size} Bilder übernommen (davon ${ctx.svgs.size} Vektorgrafiken umgewandelt)")
            add("Programmlogik nicht übernommen (${activities.size} Android-Aktivitäten) – muss nachprogrammiert werden")
        }

        out["index.html"] = indexHtml(appName, screens, analysis).toByteArray()
        out["entwurf.css"] = css(primary, background, textColor).toByteArray()
        out["entwurf.js"] = JS.toByteArray()
        out["bericht.html"] = reportHtml(appName, analysis, summary, screens.map { it.first }, activities, urls).toByteArray()
        out["entwurf.json"] = de.tvtransfer.net.Json.write(
            mapOf(
                "hinweis" to "Automatisch erzeugter Entwurf aus einer Android-APK – Oberfläche nachgebaut, Programmlogik fehlt.",
                "app" to appName, "paket" to analysis.packageName, "version" to analysis.versionName,
                "bildschirme" to screens.map { it.first }, "aktivitaeten" to activities, "adressen" to urls,
                "texte" to strings,
                "befunde" to analysis.findings.map { mapOf("bereich" to it.area, "titel" to it.title, "status" to it.status.label) },
            ),
        ).toByteArray()
        return Draft(out, screens.map { it.first }, summary)
    }

    private fun luminance(argb: Int): Double {
        fun ch(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    private val NOT_SCREENS = listOf("item_", "list_", "row_", "abc_", "mtrl_", "design_", "notification", "widget", "preference",
        "select_dialog", "support_", "m3_", "material_", "browser_actions", "custom_dialog", "ime_", "exo_", "cast_", "test_", "leak_")

    private val SCREEN_PREFIXES = listOf("activity_", "fragment_", "screen_", "main", "home", "dialog_", "layout_main", "content_")

    private fun prettyTitle(name: String) = name.removePrefix("activity_").removePrefix("fragment_").removePrefix("screen_").removePrefix("content_")
        .split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /** Wandelt einen Android-Layout-Baum in HTML um. */
    private class Ctx(val apk: File, val res: ResourceTable, val images: LinkedHashMap<String, String>) {

        fun text(a: BinaryXmlTree.Attr?): String? = when {
            a == null -> null
            a.type == ResourceTable.TYPE_REFERENCE -> res.string(a.data)
            a.raw != null -> a.raw
            else -> null
        }

        fun color(a: BinaryXmlTree.Attr?): String? = when {
            a == null -> null
            a.type in 0x1c..0x1f -> ResourceTable.css(a.data)
            a.type == ResourceTable.TYPE_REFERENCE -> res.color(a.data)
            else -> null
        }

        /** Vektorgrafiken (Android VectorDrawable), umgewandelt in SVG: Zielpfad -> SVG-Text */
        val svgs = LinkedHashMap<String, String>()

        fun image(a: BinaryXmlTree.Attr?): String? {
            if (a == null || a.type != ResourceTable.TYPE_REFERENCE) return null
            val files = res.files(a.data)
            files.firstOrNull { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".webp") }?.let { file ->
                return images.getOrPut(file) { "bilder/" + file.substringAfterLast('/') }
            }
            val xmlFile = files.firstOrNull { it.endsWith(".xml") } ?: return null
            val target = "bilder/" + xmlFile.substringAfterLast('/').removeSuffix(".xml") + ".svg"
            if (target in svgs) return target
            val svg = Zip.entry(apk, xmlFile)?.let { BinaryXmlTree.parse(it) }?.let { VectorToSvg.convert(it, res) } ?: return null
            svgs[target] = svg
            return target
        }

        fun render(n: BinaryXmlTree.Node, sb: StringBuilder, depth: Int) {
            if (depth > 40) return
            val tag = n.name.substringAfterLast('.')
            // Nur dunkle Hintergründe übernehmen – helle wären auf dem dunklen TV-Design mit heller Schrift unlesbar
            val bg = n.attrs["background"]?.let { a ->
                val argb = when { a.type in 0x1c..0x1f -> a.data; a.type == ResourceTable.TYPE_REFERENCE -> res.colorArgb(a.data); else -> null }
                argb?.takeIf { (it ushr 24) > 0 && luminance(it) < 0.3 }?.let { ResourceTable.css(it) }
            }
            val style = if (bg != null) " style=\"background:$bg\"" else ""
            val txt = esc(text(n.attrs["text"]) ?: "")
            val hint = esc(text(n.attrs["hint"]) ?: "")
            when {
                tag == "include" -> n.attrs["layout"]?.takeIf { it.type == ResourceTable.TYPE_REFERENCE }?.let { ref ->
                    res.files(ref.data).firstOrNull()?.let { f -> Zip.entry(apk, f)?.let { BinaryXmlTree.parse(it) }?.let { render(it, sb, depth + 1) } }
                }
                tag == "merge" -> n.children.forEach { render(it, sb, depth + 1) }
                tag.endsWith("Button") && !tag.contains("Radio") && !tag.contains("Toggle") || tag == "FloatingActionButton" || tag == "Chip" -> {
                    val img = image(n.attrs["srcCompat"] ?: n.attrs["src"] ?: n.attrs["icon"])
                    val label = txt.ifEmpty { esc(text(n.attrs["contentDescription"]) ?: "") }.ifEmpty { "Knopf" }
                    sb.append("<button class=\"d-btn\"$style data-todo=\"Aktion nachprogrammieren\">")
                    if (img != null) sb.append("<img src=\"$img\" alt=\"\">")
                    sb.append(label).append("</button>")
                }
                tag.contains("TextView") || tag == "Chronometer" -> if (txt.isNotEmpty()) sb.append("<p class=\"d-text\"$style>$txt</p>")
                    else sb.append("<p class=\"d-text d-empty\"$style>Text (wird im Programm gesetzt)</p>")
                tag.contains("EditText") || tag == "SearchView" ->
                    sb.append("<input class=\"d-input\" type=\"text\" placeholder=\"${hint.ifEmpty { "Eingabe" }}\">")
                tag.contains("ImageView") || tag == "ShapeableImageView" -> {
                    val img = image(n.attrs["srcCompat"] ?: n.attrs["src"])
                    if (img != null) sb.append("<img class=\"d-img\" src=\"$img\" alt=\"\">") else sb.append("<div class=\"d-img d-placeholder\">Bild</div>")
                }
                tag == "CheckBox" || tag.contains("Switch") || tag == "RadioButton" || tag.contains("Toggle") ->
                    sb.append("<label class=\"d-check\" tabindex=\"0\"><input type=\"checkbox\"> ${txt.ifEmpty { "Option" }}</label>")
                tag == "RecyclerView" || tag == "ListView" || tag == "GridView" || tag.startsWith("ViewPager") || tag == "LazyColumn" -> {
                    sb.append("<div class=\"d-list\">")
                    repeat(3) { sb.append("<div class=\"d-item\" tabindex=\"0\">Listeneintrag ${it + 1} <span>(Inhalte kommen aus dem Programmcode)</span></div>") }
                    sb.append("</div>")
                }
                tag == "WebView" -> sb.append("<div class=\"d-placeholder\">Webinhalt (Adresse siehe Bericht)</div>")
                tag.contains("ProgressBar") || tag == "SeekBar" || tag == "Slider" -> sb.append("<div class=\"d-progress\"><span></span></div>")
                tag.contains("Toolbar") || tag == "AppBarLayout" || tag == "TabLayout" || tag == "BottomNavigationView" || tag == "NavigationView" -> {
                    sb.append("<nav class=\"d-bar\"$style>")
                    if (txt.isNotEmpty()) sb.append("<b>$txt</b>")
                    n.children.forEach { render(it, sb, depth + 1) }
                    if (n.children.isEmpty() && tag != "Toolbar") sb.append("<span class=\"d-muted\">Navigation (Einträge kommen aus dem Programmcode)</span>")
                    sb.append("</nav>")
                }
                tag.contains("VideoView") || tag.contains("PlayerView") || tag.contains("SurfaceView") || tag.contains("TextureView") ->
                    sb.append("<div class=\"d-video\">Videobereich – Wiedergabe muss für Samsung (AVPlay) nachprogrammiert werden</div>")
                tag == "fragment" || tag == "FragmentContainerView" || tag == "NavHostFragment" ->
                    sb.append("<div class=\"d-placeholder\">Unterbereich (wird im Programm eingesetzt)</div>")
                tag == "View" || tag == "Space" || tag == "Divider" || tag.contains("Divider") -> sb.append("<div class=\"d-space\"$style></div>")
                else -> { // Container (LinearLayout, ConstraintLayout, FrameLayout, CardView, ScrollView …)
                    val horizontal = n.attrs["orientation"]?.data == 0 && tag.contains("LinearLayout")
                    val cls = buildString {
                        append("d-box")
                        if (horizontal) append(" d-row")
                        if (tag.contains("Card")) append(" d-card")
                    }
                    sb.append("<div class=\"$cls\"$style>")
                    n.children.forEach { render(it, sb, depth + 1) }
                    sb.append("</div>")
                }
            }
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun indexHtml(name: String, screens: List<Pair<String, String>>, a: Analysis): String {
        val nav = screens.mapIndexed { i, (t, _) -> "<button class=\"d-nav\" data-screen=\"$i\">${esc(t)}</button>" }.joinToString("")
        val pages = screens.mapIndexed { i, (t, h) -> "<section class=\"d-screen\" id=\"s$i\" data-title=\"${esc(t)}\">$h</section>" }.joinToString("\n")
        val empty = if (screens.isEmpty()) "<section class=\"d-screen\" id=\"s0\"><div class=\"d-placeholder\">Keine Android-Layouts gefunden. Die Oberfläche dieser App ist im Programmcode beschrieben (z. B. Jetpack Compose oder Flutter) und muss für den Fernseher neu gestaltet werden. Details im Bericht.</div></section>" else ""
        return """<!doctype html>
<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=1920">
<title>${esc(name)}</title><link rel="stylesheet" href="entwurf.css"></head>
<body>
<header class="d-head"><img src="tv-icon.png" alt="" onerror="this.style.display='none'"><h1>${esc(name)}</h1>
<span class="d-badge">Entwurf aus Android-App – Funktionen müssen nachprogrammiert werden</span>
<a class="d-nav d-report" href="bericht.html">Bericht</a></header>
<div class="d-main"><aside class="d-side">$nav</aside><main class="d-content">
$pages$empty
</main></div>
<!-- Erzeugt von TV-App-Transfer (Entwurf-Konverter) aus ${esc(a.packageName ?: "?")} ${esc(a.versionName ?: "")} -->
<script src="entwurf.js"></script>
</body></html>"""
    }

    private fun reportHtml(name: String, a: Analysis, summary: List<String>, screens: List<String>, activities: List<String>, urls: List<String>): String {
        fun li(items: List<String>) = items.joinToString("") { "<li>${esc(it)}</li>" }
        val findings = a.findings.joinToString("") { "<li><b>${esc(it.area)}:</b> ${esc(it.title)} – <i>${esc(it.status.label)}</i><br><small>${esc(it.detail)}</small></li>" }
        return """<!doctype html><html lang="de"><head><meta charset="utf-8"><title>Bericht</title><link rel="stylesheet" href="entwurf.css"></head>
<body class="d-reportpage"><script src="tv-bridge.js"></script><a class="d-nav" href="index.html">← Zurück</a>
<h1>Konverter-Bericht: ${esc(name)}</h1>
<p>Dieser Entwurf wurde automatisch aus der Android-App <b>${esc(a.packageName ?: "?")}</b> (Version ${esc(a.versionName ?: "?")}) erzeugt.
Die Oberfläche ist nachgebaut, die Programmlogik nicht. Für eine funktionierende App muss sie nachprogrammiert werden.</p>
<h2>Zusammenfassung</h2><ul>${li(summary)}</ul>
<h2>Bildschirme</h2><ul>${li(screens.ifEmpty { listOf("keine") })}</ul>
<h2>Android-Aktivitäten (Programmlogik)</h2><ul>${li(activities.ifEmpty { listOf("keine gefunden") })}</ul>
<h2>Gefundene Webadressen / Schnittstellen</h2><ul>${li(urls.ifEmpty { listOf("keine gefunden") })}</ul>
<h2>Analyse</h2><ul>$findings</ul>
<p><small>Alle Texte und Befunde stehen auch maschinenlesbar in entwurf.json.</small></p>
</body></html>"""
    }

    private fun css(primary: String, bg: String, fg: String) = """
:root{--p:$primary;--bg:$bg;--fg:$fg}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font-family:Tizen,Roboto,Arial,sans-serif;font-size:28px}
.d-head{display:flex;align-items:center;gap:24px;padding:24px 48px;background:rgba(0,0,0,.25)}
.d-head img{width:64px;height:64px}.d-head h1{margin:0;font-size:40px}
.d-badge{background:#FBBF24;color:#111;border-radius:8px;padding:6px 14px;font-size:20px}
.d-report{margin-left:auto}
.d-main{display:flex;height:calc(100vh - 112px)}
.d-side{width:380px;padding:24px;display:flex;flex-direction:column;gap:12px;overflow:auto;background:rgba(0,0,0,.18)}
.d-content{flex:1;padding:32px 48px;overflow:auto}
.d-nav{background:transparent;color:var(--fg);border:2px solid rgba(255,255,255,.2);border-radius:12px;padding:14px 18px;font-size:24px;text-align:left;text-decoration:none}
.d-nav.active{background:var(--p);color:#fff;border-color:var(--p)}
.d-screen{display:none}.d-screen.active{display:block}
.d-box{display:flex;flex-direction:column;gap:14px}.d-row{flex-direction:row;flex-wrap:wrap;align-items:center}
.d-card{background:rgba(255,255,255,.06);border-radius:16px;padding:20px}
.d-text{margin:0}.d-empty,.d-muted{opacity:.5;font-style:italic}
.d-btn{background:var(--p);color:#fff;border:0;border-radius:12px;padding:14px 26px;font-size:26px;display:inline-flex;align-items:center;gap:10px;align-self:flex-start}
.d-btn img{width:36px;height:36px}
.d-input{font-size:26px;padding:12px;border-radius:10px;border:2px solid rgba(255,255,255,.3);background:rgba(0,0,0,.2);color:var(--fg);max-width:800px}
.d-img{max-width:480px;max-height:320px;object-fit:contain}
.d-placeholder,.d-video{border:2px dashed rgba(255,255,255,.3);border-radius:12px;padding:24px;opacity:.75}
.d-video{min-height:300px;display:flex;align-items:center;justify-content:center;background:#000}
.d-list{display:flex;flex-direction:column;gap:10px}
.d-item{background:rgba(255,255,255,.06);border-radius:10px;padding:16px}.d-item span{opacity:.5;font-size:20px}
.d-progress{height:10px;background:rgba(255,255,255,.15);border-radius:5px;max-width:600px}.d-progress span{display:block;width:40%;height:100%;background:var(--p);border-radius:5px}
.d-bar{display:flex;gap:16px;align-items:center;padding:12px 16px;border-radius:12px;background:rgba(255,255,255,.08)}
.d-space{min-height:8px}
.d-check{display:flex;gap:12px;align-items:center}
.d-reportpage{padding:48px;font-size:24px}.d-reportpage li{margin:6px 0}
:focus{outline:4px solid #4da3ff;outline-offset:3px}
"""

    private const val JS = """
(function(){
  var navs=document.querySelectorAll('.d-nav[data-screen]'), screens=document.querySelectorAll('.d-screen');
  function show(i){
    for(var k=0;k<screens.length;k++) screens[k].className='d-screen'+(k==i?' active':'');
    for(var j=0;j<navs.length;j++) navs[j].className='d-nav'+(j==i?' active':'');
  }
  for(var n=0;n<navs.length;n++)(function(b){b.addEventListener('click',function(){show(+b.getAttribute('data-screen'));});})(navs[n]);
  show(0);
  if(navs.length) navs[0].focus();
  var todo=document.querySelectorAll('[data-todo]');
  for(var t=0;t<todo.length;t++) todo[t].addEventListener('click',function(){
    var m=document.getElementById('d-msg'); if(!m){m=document.createElement('div');m.id='d-msg';m.style.cssText='position:fixed;bottom:40px;left:50%;transform:translateX(-50%);background:#FBBF24;color:#111;padding:16px 28px;border-radius:12px;font-size:24px';document.body.appendChild(m);}
    m.textContent='Entwurf: Diese Funktion muss noch nachprogrammiert werden.'; m.style.display='block'; setTimeout(function(){m.style.display='none';},2500);
  });
})();
"""
}

/** Wandelt eine Android-Vektorgrafik (VectorDrawable) in SVG um – Pfade, Gruppen, Füll- und Linienfarben. */
internal object VectorToSvg {
    fun convert(root: BinaryXmlTree.Node, res: ResourceTable): String? {
        if (root.name != "vector") return null
        val vw = num(root.attrs["viewportWidth"]) ?: return null
        val vh = num(root.attrs["viewportHeight"]) ?: return null
        val sb = StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $vw $vh\" width=\"$vw\" height=\"$vh\">")
        root.children.forEach { node(it, res, sb) }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun node(n: BinaryXmlTree.Node, res: ResourceTable, sb: StringBuilder) {
        when (n.name) {
            "group" -> {
                val tx = num(n.attrs["translateX"]) ?: 0f; val ty = num(n.attrs["translateY"]) ?: 0f
                val sx = num(n.attrs["scaleX"]) ?: 1f; val sy = num(n.attrs["scaleY"]) ?: 1f
                val rot = num(n.attrs["rotation"]) ?: 0f
                val px = num(n.attrs["pivotX"]) ?: 0f; val py = num(n.attrs["pivotY"]) ?: 0f
                sb.append("<g transform=\"translate($tx $ty) translate($px $py) rotate($rot) scale($sx $sy) translate(${-px} ${-py})\">")
                n.children.forEach { node(it, res, sb) }
                sb.append("</g>")
            }
            "path" -> {
                val d = n.attrs["pathData"]?.let { a -> a.raw ?: if (a.type == ResourceTable.TYPE_REFERENCE) res.string(a.data) else null } ?: return
                val fill = color(n.attrs["fillColor"], res) ?: if (n.attrs.containsKey("strokeColor")) "none" else "#FFFFFF"
                val stroke = color(n.attrs["strokeColor"], res)
                val sw = num(n.attrs["strokeWidth"])
                val fillAlpha = num(n.attrs["fillAlpha"])
                sb.append("<path d=\"").append(d.replace("\"", "")).append("\" fill=\"$fill\"")
                if (fillAlpha != null) sb.append(" fill-opacity=\"$fillAlpha\"")
                if (stroke != null) sb.append(" stroke=\"$stroke\"")
                if (sw != null) sb.append(" stroke-width=\"$sw\"")
                if (n.attrs["fillType"]?.data == 1) sb.append(" fill-rule=\"evenodd\"")
                sb.append("/>")
            }
        }
    }

    /** Farbe; Theme-Verweise (?attr/…) gibt es außerhalb der App nicht → Weiß (gut sichtbar auf dunklem TV-Design). */
    private fun color(a: BinaryXmlTree.Attr?, res: ResourceTable): String? = when {
        a == null -> null
        a.type in 0x1c..0x1f -> ResourceTable.css(a.data)
        a.type == ResourceTable.TYPE_REFERENCE -> res.color(a.data) ?: "#FFFFFF"
        a.type == 0x02 -> "#FFFFFF"
        else -> null
    }

    /** Zahl aus einem Attribut (Float, Ganzzahl, Dimension oder Text). */
    private fun num(a: BinaryXmlTree.Attr?): Float? = when {
        a == null -> null
        a.type == 0x04 -> java.lang.Float.intBitsToFloat(a.data)
        a.type == 0x10 || a.type == 0x11 -> a.data.toFloat()
        a.type == 0x05 -> complexToFloat(a.data)
        a.raw != null -> a.raw.filter { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull()
        else -> null
    }

    private fun complexToFloat(c: Int): Float {
        val radix = (c shr 4) and 0x3
        val mantissa = (c shr 8).toFloat()
        val mult = floatArrayOf(1f / (1 shl 0), 1f / (1 shl 7), 1f / (1 shl 15), 1f / (1 shl 23))[radix]
        return mantissa * mult
    }
}
