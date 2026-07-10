package com.digitalsignage.player.ui.screens

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.digitalsignage.player.data.api.ApiClient
import com.digitalsignage.player.data.api.models.PlaylistItem
import com.digitalsignage.player.data.cache.TemplateZoneMediaCache
import com.digitalsignage.player.ui.theme.BlackCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders a template playlist item using an Android WebView.
 *
 * Why WebView?
 * - Templates are composed of arbitrary zones (clock, text, image, video, wayfinding, queue board…).
 * - Reimplementing every widget type in Compose would duplicate the web renderer and lag behind
 *   new widget types added to the CMS.
 * - A WebView gives us the same rendering stack as the web player at near-native speed.
 *
 * Architecture:
 * 1. Build a self-contained HTML string that includes the template data as an inline JSON blob.
 * 2. A JavaScript renderer positions zones as absolutely-placed divs scaled to fill the screen.
 * 3. Media URLs are resolved relative to the API base URL so images load correctly.
 * 4. The WebView is destroyed when the composable leaves composition.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TemplateWebRenderer(
    item: PlaylistItem,
    ownsPlaybackClock: Boolean,
    onDisplayReady: () -> Unit,
    onFinished: () -> Unit,
    onStageFailed: () -> Unit = onFinished,
    widgetOverlayOnly: Boolean = false
) {
    val template = item.template
    val context = LocalContext.current
    val onDisplayReadyState by rememberUpdatedState(onDisplayReady)
    val onFinishedState by rememberUpdatedState(onFinished)
    val onStageFailedState by rememberUpdatedState(onStageFailed)
    val ownsClockState by rememberUpdatedState(ownsPlaybackClock)
    var renderError by remember(item.playbackKey()) { mutableStateOf<String?>(null) }

    // Fallback: if no template data came through, skip gracefully
    if (template == null) {
        LaunchedEffect(item.playbackKey()) {
            onDisplayReadyState()
            delay(item.effectiveDurationSeconds() * 1000L)
            if (ownsClockState) onFinishedState()
        }
        Box(Modifier.fillMaxSize().background(BlackCanvas), contentAlignment = Alignment.Center) {
            Text("Template: no data received", color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    if (renderError != null) {
        LaunchedEffect(renderError) {
            delay(500)
            onStageFailedState()
        }
        Box(Modifier.fillMaxSize().background(BlackCanvas))
        return
    }

    // Playback duration clock
    LaunchedEffect(item.playbackKey(), ownsPlaybackClock) {
        if (!ownsPlaybackClock) return@LaunchedEffect
        delay(item.effectiveDurationSeconds() * 1000L)
        onFinishedState()
    }

    var localZonesJson by remember(item.playbackKey(), widgetOverlayOnly) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.playbackKey(), template.zonesJson, widgetOverlayOnly) {
        val raw = template.zonesJson ?: "[]"
        localZonesJson = if (widgetOverlayOnly) {
            filterWidgetZonesJson(raw)
        } else {
            TemplateZoneMediaCache.localizeZones(context, raw)
        }
    }

    LaunchedEffect(localZonesJson, widgetOverlayOnly) {
        if (widgetOverlayOnly && (localZonesJson == "[]" || localZonesJson.isNullOrBlank())) {
            onDisplayReadyState()
        }
    }

    if (localZonesJson == null) {
        Box(Modifier.fillMaxSize().background(if (widgetOverlayOnly) Color.Transparent else BlackCanvas))
        return
    }

    val mediaBase = remember { ApiClient.mediaBaseUrl() }
    val htmlContent = remember(item.playbackKey(), localZonesJson, widgetOverlayOnly) {
        buildTemplateHtml(
            canvasW = template.canvasWidth(),
            canvasH = template.canvasHeight(),
            backgroundColor = if (widgetOverlayOnly) "transparent" else (template.backgroundColor ?: "#000000"),
            zonesJson = localZonesJson ?: "[]",
            mediaBaseUrl = mediaBase,
            widgetsOnly = widgetOverlayOnly
        )
    }

    val webView = remember(item.playbackKey()) {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                loadsImagesAutomatically = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    android.util.Log.d("TemplateWebRenderer", "[${msg.messageLevel()}] ${msg.message()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    onDisplayReadyState()
                }
                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        renderError = error?.description?.toString()
                    } else {
                        android.util.Log.w(
                            "TemplateWebRenderer",
                            "Subresource error: ${request?.url} — ${error?.description}"
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    LaunchedEffect(htmlContent, localZonesJson) {
        // ponytail: loadDataWithBaseURL + file:// zone media = decoded video (MediaCodec) but blank
        // compositor; write HTML beside cached media and loadUrl so origins match.
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "template_pages").apply { mkdirs() }
            val page = File(dir, "${item.playbackKey().hashCode()}.html")
            page.writeText(htmlContent)
            withContext(Dispatchers.Main) {
                webView.loadUrl("file://${page.absolutePath}")
            }
        }
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize().background(if (widgetOverlayOnly) Color.Transparent else Color.Unspecified)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  HTML template renderer
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Builds a self-contained HTML page that renders the template.
 *
 * The page:
 * - Fills the entire viewport regardless of the TV's physical resolution.
 * - Scales the template canvas proportionally (letterbox if aspect ratios differ).
 * - Renders each zone as a positioned <div> with the correct widget content.
 *
 * Supported zone types:
 *   media (image)  — <img> with objectFit
 *   media (video)  — <video> muted autoplay loop
 *   widget: text, clock, countdown, rss/ticker, weather, qrcode, wayfinding, queue_board,
 *           bed_status_board, schedule_board — rendered as styled divs / live JS widgets
 *   widget: webview — rendered as sandboxed <iframe>
 *   Unsupported types show an empty transparent zone (no placeholder text on real displays).
 */
private fun buildTemplateHtml(
    canvasW: Int,
    canvasH: Int,
    backgroundColor: String,
    zonesJson: String,
    mediaBaseUrl: String,
    widgetsOnly: Boolean = false
): String {
    val bodyBg = if (widgetsOnly) "transparent" else "#000"
    // language=HTML
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<style>
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:100%;height:100%;overflow:hidden;background:$bodyBg;font-family:Arial,sans-serif}
#stage{position:absolute;left:0;top:0;width:100%;height:100%;overflow:hidden}
.zone{position:absolute;overflow:hidden}
.zone-media img,.zone-media video{width:100%;height:100%;display:block}
.zone-widget{width:100%;height:100%;display:flex;align-items:center;justify-content:center}
.clock-text{text-align:center;line-height:1.1}
.text-content{width:100%;height:100%;word-break:break-word;overflow:hidden}
.ticker-wrap{width:100%;height:100%;overflow:hidden;display:flex;align-items:center}
.ticker-inner{white-space:nowrap;display:inline-block;-webkit-animation:ticker linear infinite;animation:ticker linear infinite}
@-webkit-keyframes ticker{0%{transform:translateX(100%)}100%{transform:translateX(-100%)}}
@keyframes ticker{0%{transform:translateX(100%)}100%{transform:translateX(-100%)}}
.qr-canvas{display:block;margin:auto}
.webview-iframe{width:100%;height:100%;border:none}
</style>
</head>
<body>
<div id="stage" style="background:${backgroundColor}"></div>
<script>
(function(){
  var MEDIA_BASE = "${mediaBaseUrl.replace("\"", "\\\"")}";
  var zones = ${zonesJson.ifBlank { "[]" }};
  var cw = ${canvasW}, ch = ${canvasH};

  // Percent-based layout — avoids CSS transform:scale() which breaks WebView video on Android.
  function pctX(px){ return (px / cw * 100) + '%'; }
  function pctY(px){ return (px / ch * 100) + '%'; }

  function resolveUrl(url){
    if(!url) return null;
    if(url.startsWith('file://')) return url;
    if(url.startsWith('http://') || url.startsWith('https://')) return url;
    return MEDIA_BASE + (url.startsWith('/') ? url : '/'+url);
  }

  function safeStr(v){ return v != null ? String(v) : ''; }
  function safeNum(v, def){ var n = parseFloat(v); return isNaN(n) ? def : n; }
  function safeColor(v, def){ return (v && /^#[0-9a-fA-F]{3,8}${'$'}|^rgb/.test(v)) ? v : def; }

  zones.sort(function(a,b){ return (a.zIndex||0)-(b.zIndex||0); });

  zones.forEach(function(zone){
    // Visibility: zones without isVisible field are visible; explicitly false means hidden.
    if(zone.isVisible === false) return;

    var div = document.createElement('div');
    div.className = 'zone';
    div.style.left   = pctX(safeNum(zone.x, 0));
    div.style.top    = pctY(safeNum(zone.y, 0));
    div.style.width  = pctX(safeNum(zone.width, 100));
    div.style.height = pctY(safeNum(zone.height, 100));
    div.style.zIndex = zone.zIndex || 0;

    var op = zone.opacity != null ? (zone.opacity > 1 ? zone.opacity/100 : zone.opacity) : 1;
    div.style.opacity = op;
    if(zone.borderRadius) div.style.borderRadius = zone.borderRadius+'px';
    if(zone.blendMode && zone.blendMode !== 'normal') div.style.mixBlendMode = zone.blendMode;

    var ct = (zone.contentType || zone.content_type || '').toLowerCase();
    var wt = (zone.widgetType  || zone.widget_type  || '').toLowerCase();
    var cfg = zone.widgetConfig || zone.widget_config || {};

    if(ct === 'media' && ${widgetsOnly}){
      return;
    }
    if(ct === 'media'){
      renderMedia(div, zone);
    } else if(ct === 'widget'){
      renderWidget(div, wt, cfg, zone);
    } else if(ct === 'text'){
      // Legacy text zone
      renderTextWidget(div, { text: zone.textContent || '' });
    }

    document.getElementById('stage').appendChild(div);
  });

  /* ── Media ── */
  function renderMedia(div, zone){
    div.className += ' zone-media';
    var fit = (zone.mediaFit || zone.media_fit || 'cover');

    // Multi-slide zone: cycle through images/videos on loop, one at a time.
    var items = Array.isArray(zone.mediaItems) ? zone.mediaItems : null;
    if(items && items.length > 1){
      renderMediaSlideshow(div, items, fit);
      return;
    }

    var asset = zone.mediaAsset || zone.media_asset || (items && items[0] && items[0].mediaAsset);
    renderMediaSlide(div, asset, zone.url, fit, false, null);
  }

  /** Renders a single image/video into `container`, replacing any prior content. */
  function renderMediaSlide(container, asset, fallbackUrl, fit, loopVideo, onVideoEnded){
    container.innerHTML = '';
    var ft = (asset ? (asset.fileType || asset.file_type) : null) || '';
    ft = ft.toLowerCase();

    var url = asset ? resolveUrl(asset.url) : null;
    var thumb = asset ? resolveUrl(asset.thumbnailUrl || asset.thumbnail_url) : null;
    if(!url && fallbackUrl) url = resolveUrl(fallbackUrl);
    if(!url && !thumb) return;

    if(ft === 'video'){
      var v = document.createElement('video');
      v.setAttribute('playsinline', 'true');
      v.setAttribute('webkit-playsinline', 'true');
      v.muted = true;
      v.autoplay = true;
      v.loop = loopVideo;
      v.playsInline = true;
      v.preload = 'auto';
      v.style.objectFit = fit;
      v.style.width = '100%';
      v.style.height = '100%';
      v.src = url || thumb;
      if(onVideoEnded) v.addEventListener('ended', onVideoEnded);
      container.appendChild(v);
      v.addEventListener('canplay', function(){ v.play().catch(function(e){
        console.log('video play retry: '+e);
      }); });
      v.play().catch(function(){});
    } else {
      var img = document.createElement('img');
      img.alt = '';
      img.style.objectFit = fit;
      img.style.width = '100%';
      img.style.height = '100%';
      // ponytail: TV WebView chokes on 10MB+ originals — try thumbnail first, fall back to full url
      img.src = thumb || url;
      img.onerror = function(){
        if(url && img.src !== url){ img.src = url; }
      };
      container.appendChild(img);
    }
  }

  /** Cycles a zone through an ordered list of slides on loop: images by timer, videos by 'ended'. */
  function renderMediaSlideshow(div, items, fit){
    var idx = 0;
    var timer = null;

    function showCurrent(){
      if(timer){ clearTimeout(timer); timer = null; }
      var item = items[idx % items.length];
      var asset = item.mediaAsset || item.media_asset;
      var isImage = asset && ((asset.fileType || asset.file_type || '').toLowerCase() !== 'video');

      renderMediaSlide(div, asset, null, fit, false, isImage ? null : advance);

      if(isImage){
        var seconds = safeNum(item.duration, 5);
        timer = setTimeout(advance, Math.max(1, seconds) * 1000);
      }
    }
    function advance(){
      idx = (idx + 1) % items.length;
      showCurrent();
    }
    showCurrent();
  }

  /* ── Widget dispatcher ── */
  function renderWidget(div, type, cfg, zone){
    div.className += ' zone-widget';
    switch(type){
      case 'clock':       renderClockWidget(div, cfg); break;
      case 'text':        renderTextWidget(div, cfg); break;
      case 'countdown':   renderCountdownWidget(div, cfg); break;
      case 'rss':
      case 'ticker':    renderTickerWidget(div, cfg); break;
      case 'weather':     renderWeatherWidget(div, cfg); break;
      case 'qrcode':      renderQrWidget(div, cfg); break;
      case 'webview':     renderWebviewWidget(div, cfg); break;
      case 'queue_board': renderQueueBoard(div, cfg); break;
      case 'bed_status_board': renderBedStatusBoard(div, cfg); break;
      case 'schedule_board':   renderScheduleBoard(div, cfg); break;
      case 'wayfinding':  renderWayfinding(div, cfg); break;
      // shape, chart, social, html — show transparent
      default: break;
    }
  }

  /* ── Clock ── */
  function renderClockWidget(div, cfg){
    var color  = safeColor(cfg.textColor || (cfg.font && cfg.font.color), '#ffffff');
    var size   = safeNum(cfg.fontSize || (cfg.font && cfg.font.size), 48);
    var family = safeStr(cfg.fontFamily || (cfg.font && cfg.font.family) || 'Arial');
    var weight = safeStr(cfg.fontWeight || 'bold');
    var fmt    = safeStr(cfg.timeFormat || cfg.format || '12h');
    var showSec= cfg.showSeconds !== false;
    var showDate = cfg.showDate === true;
    var tz     = safeStr(cfg.timezone || '');

    var wrap = document.createElement('div');
    wrap.className = 'clock-text';
    wrap.style.cssText = 'color:'+color+';font-size:'+size+'px;font-family:'+family+
      ';font-weight:'+weight+';width:100%;height:100%;display:flex;flex-direction:column;'+
      'align-items:center;justify-content:center';
    div.appendChild(wrap);

    function tick(){
      var now = tz ? new Date(new Date().toLocaleString('en-US',{timeZone:tz})) : new Date();
      var h = now.getHours(), m = now.getMinutes(), s = now.getSeconds();
      var ampm = '';
      if(fmt === '12h'){ ampm = h>=12?' PM':' AM'; h = h%12||12; }
      var t = pad(h)+':'+pad(m)+(showSec?':'+pad(s):'')+ampm;
      var html = '<span>'+t+'</span>';
      if(showDate){
        var d = now.toLocaleDateString(undefined,{weekday:'short',month:'short',day:'numeric'});
        html += '<span style="font-size:0.45em;margin-top:0.2em;opacity:0.8">'+d+'</span>';
      }
      wrap.innerHTML = html;
    }
    tick(); setInterval(tick, 1000);
  }

  /* ── Text ── */
  function renderTextWidget(div, cfg){
    var st = cfg.style || {};
    var text  = safeStr(cfg.content || cfg.text || '');
    var color = safeColor(st.color || cfg.textColor || cfg.color, '#ffffff');
    var size  = safeNum(st.fontSize || cfg.fontSize, 24);
    var family= safeStr(st.fontFamily || cfg.fontFamily || 'Arial');
    var weight= safeStr(st.fontWeight || cfg.fontWeight || 'normal');
    var align = safeStr(st.textAlign  || cfg.alignment  || 'left');
    var lh    = safeNum(st.lineHeight || cfg.lineHeight, 1.4);
    var pad   = safeStr(st.padding    || cfg.padding    || '8px');
    var bg    = safeColor(st.backgroundColor || cfg.backgroundColor, '');

    div.style.alignItems = 'flex-start';
    div.style.justifyContent = 'flex-start';

    var p = document.createElement('div');
    p.className = 'text-content';
    p.style.cssText = 'color:'+color+';font-size:'+size+'px;font-family:'+family+
      ';font-weight:'+weight+';text-align:'+align+';line-height:'+lh+';padding:'+pad+
      (bg?';background:'+bg:'');

    var anim = cfg.animation || '';
    if(anim === 'marquee' || anim === 'scroll-left' || anim === 'scroll'){
      var inner = document.createElement('span');
      inner.className = 'ticker-inner';
      inner.textContent = text;
      var speed = safeNum(cfg.animationSpeed, 50);
      inner.style.animationDuration = Math.max(2, text.length * 0.15 / (speed/100)) + 's';
      p.style.overflow = 'hidden'; p.style.whiteSpace = 'nowrap';
      p.appendChild(inner);
    } else {
      p.textContent = text;
    }
    div.appendChild(p);
  }

  /* ── Countdown ── */
  function renderCountdownWidget(div, cfg){
    var target = cfg.targetDate ? new Date(cfg.targetDate).getTime() : Date.now() + 3600000;
    var color  = safeColor(cfg.textColor || '#ffffff', '#ffffff');
    var size   = safeNum(cfg.fontSize, 48);
    var family = safeStr(cfg.fontFamily || 'Arial');
    var label  = safeStr(cfg.label || '');

    var wrap = document.createElement('div');
    wrap.style.cssText = 'color:'+color+';font-size:'+size+'px;font-family:'+family+
      ';text-align:center;width:100%;height:100%;display:flex;flex-direction:column;'+
      'align-items:center;justify-content:center';
    if(label){ var lbl = document.createElement('div'); lbl.style.fontSize='0.4em';
      lbl.textContent=label; wrap.appendChild(lbl); }
    var timer = document.createElement('div');
    wrap.appendChild(timer);
    div.appendChild(wrap);

    function tick(){
      var diff = target - Date.now();
      if(diff < 0) diff = 0;
      var d = Math.floor(diff/86400000), h = Math.floor((diff%86400000)/3600000),
          m = Math.floor((diff%3600000)/60000), s = Math.floor((diff%60000)/1000);
      timer.textContent = (d>0?d+'d ':'') + pad(h)+':'+pad(m)+':'+pad(s);
    }
    tick(); setInterval(tick, 1000);
  }

  /* ── RSS ticker ── */
  function renderTickerWidget(div, cfg){
    var color = safeColor(cfg.textColor || '#ffffff', '#ffffff');
    var size  = safeNum(cfg.fontSize, 20);
    var items = cfg.items || cfg.staticItems || ['News ticker…'];
    var text  = Array.isArray(items) ? items.join('   ·   ') : safeStr(items);

    var wrap = document.createElement('div');
    wrap.className = 'ticker-wrap';
    wrap.style.cssText = 'color:'+color+';font-size:'+size+'px';
    var inner = document.createElement('span');
    inner.className = 'ticker-inner';
    inner.textContent = text;
    var spd = safeNum(cfg.speed || cfg.animationSpeed, 50);
    inner.style.animationDuration = Math.max(4, text.length * 0.08) + 's';
    wrap.appendChild(inner);
    div.appendChild(wrap);
  }

  /* ── Weather ── */
  function renderWeatherWidget(div, cfg){
    var color = safeColor(cfg.textColor || (cfg.font && cfg.font.color) || '#ffffff', '#ffffff');
    var size  = safeNum(cfg.fontSize || (cfg.font && cfg.font.size), 24);
    div.innerHTML = '<div style="color:'+color+';font-size:'+size+'px;text-align:center;'+
      'width:100%;height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center">'+
      '<div style="font-size:2em">&#9925;</div>'+
      '<div>Weather</div></div>';
  }

  /* ── QR Code (simple URL display fallback — full QR needs library) ── */
  function renderQrWidget(div, cfg){
    var url = safeStr(cfg.text || cfg.url || '');
    var bg  = safeColor(cfg.backgroundColor || (cfg.color && cfg.color.light) || '#ffffff', '#ffffff');
    var fg  = safeColor(cfg.foregroundColor  || (cfg.color && cfg.color.dark)  || '#000000', '#000000');
    // Display a basic representation; a full QR library would be a future enhancement.
    div.innerHTML = '<div style="background:'+bg+';color:'+fg+';width:100%;height:100%;'+
      'display:flex;flex-direction:column;align-items:center;justify-content:center;'+
      'font-size:12px;padding:8px;word-break:break-all;text-align:center">'+
      '<div style="font-size:3em;margin-bottom:8px">&#9638;</div>'+
      '<div>'+escHtml(url)+'</div></div>';
  }

  /* ── WebView iframe ── */
  function renderWebviewWidget(div, cfg){
    var url = safeStr(cfg.url || cfg.href || '');
    if(!url){ return; }
    var iframe = document.createElement('iframe');
    iframe.className = 'webview-iframe';
    iframe.src = url;
    iframe.sandbox = 'allow-scripts allow-same-origin allow-forms';
    iframe.referrerPolicy = 'no-referrer';
    div.appendChild(iframe);
  }

  /* ── Live data boards (queue / bed / schedule) ── */
  function renderQueueBoard(div, cfg){
    var title = safeStr(cfg.title || 'Now Serving');
    var color = safeColor((cfg.font && cfg.font.color) || '#ffffff', '#ffffff');
    div.style.flexDirection = 'column';
    div.style.background = 'rgba(13,17,23,0.85)';
    div.style.borderRadius = '12px';
    div.innerHTML = '<div style="color:'+color+';text-align:center;width:100%;height:100%;'+
      'display:flex;flex-direction:column;align-items:center;justify-content:center">'+
      '<div style="font-size:0.7em;opacity:0.7;text-transform:uppercase;letter-spacing:.08em">'+escHtml(title)+'</div>'+
      '<div style="font-size:2.8em;font-weight:800;margin:8px 0">&#8212;</div>'+
      '<div style="font-size:0.55em;opacity:0.6">Fetching queue data…</div></div>';
  }

  function renderBedStatusBoard(div, cfg){
    var color = safeColor((cfg.font && cfg.font.color) || '#ffffff', '#ffffff');
    div.style.background = 'rgba(13,17,23,0.85)';
    div.style.borderRadius = '12px';
    div.innerHTML = '<div style="color:'+color+';text-align:center;padding:16px">'+
      '<div style="font-size:1em;font-weight:700;margin-bottom:8px">Bed Status</div>'+
      '<div style="font-size:0.7em;opacity:0.6">Connecting to HIS…</div></div>';
  }

  function renderScheduleBoard(div, cfg){
    var title = safeStr(cfg.title || 'Schedule');
    var color = safeColor((cfg.font && cfg.font.color) || '#ffffff', '#ffffff');
    div.style.background = 'rgba(13,17,23,0.85)';
    div.style.borderRadius = '12px';
    div.innerHTML = '<div style="color:'+color+';padding:16px;width:100%;height:100%">'+
      '<div style="font-size:1em;font-weight:700;margin-bottom:8px">'+escHtml(title)+'</div>'+
      '<div style="font-size:0.7em;opacity:0.6">Connecting to schedule feed…</div></div>';
  }

  /* ── Wayfinding ── */
  function renderWayfinding(div, cfg){
    var color = safeColor((cfg.font && cfg.font.color) || '#ffffff', '#ffffff');
    var depts = cfg.departments || [];
    div.style.background = 'rgba(13,17,23,0.9)';
    div.style.borderRadius = '12px';
    div.style.flexDirection = 'column';
    div.style.alignItems = 'flex-start';
    div.style.padding = '16px';
    div.style.overflowY = 'auto';
    div.style.color = color;

    if(depts.length === 0){
      div.innerHTML = '<div style="opacity:0.5;padding:8px">Wayfinding directory</div>';
      return;
    }
    var html = '';
    depts.forEach(function(d){
      var arrow = d.direction === 'right' ? '&#8594;' : d.direction === 'up' ? '&#8593;' : '&#8592;';
      html += '<div style="display:flex;align-items:center;gap:12px;padding:6px 8px;'+
        'border-left:3px solid rgba(255,255,255,0.3);margin-bottom:6px">'+
        '<span style="flex:1;font-weight:600">'+escHtml(d.name||'')+'</span>'+
        '<span style="opacity:0.7;font-size:0.85em">Floor '+escHtml(d.floor||'')+'</span>'+
        '<span style="font-size:1.2em">'+arrow+'</span>'+
        '</div>';
    });
    div.innerHTML = html;
  }

  /* ── Utilities ── */
  function pad(n){ return String(n).padStart(2,'0'); }
  function escHtml(s){ var d=document.createElement('div'); d.textContent=s; return d.innerHTML; }
})();
</script>
</body>
</html>
""".trimIndent()
}
