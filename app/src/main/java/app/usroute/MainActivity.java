package app.usroute;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
 private final Handler ui=new Handler(Looper.getMainLooper());
 private final ExecutorService coordinator=Executors.newSingleThreadExecutor(),checks=Executors.newFixedThreadPool(8);
 private final java.util.concurrent.atomic.AtomicInteger generation=new java.util.concurrent.atomic.AtomicInteger();
 private List<ProxyProfile> profiles=new ArrayList<>();
 private ProxyBridge bridge; private ProfileStore store; private ProxyProfile selected;
 private boolean ready=false,busy=false,closed=false,foreground=true,enabled=false;
 private WebView web; private TextView status,list,detail; private EditText address; private Button connect;
 private LinearLayout settings; private final int ink=Color.rgb(22,35,54),accent=Color.rgb(24,109,95);
 private long lastScan=0; private String pending="https://example.com";
 private final Runnable health=()->{if(enabled&&foreground&&!closed)scan(false);};
 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
 @Override public void onCreate(Bundle state){
  super.onCreate(state); getWindow().setStatusBarColor(ink); getWindow().setNavigationBarColor(ink);
  store=new ProfileStore(this);
  LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(245,248,250));
  root.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(in.getSystemWindowInsetLeft(),in.getSystemWindowInsetTop(),in.getSystemWindowInsetRight(),in.getSystemWindowInsetBottom());return in;});
  setContentView(root);
  TextView title=text("US ROUTE  /  браузер",22); title.setTextColor(Color.WHITE); title.setBackgroundColor(ink); title.setPadding(dp(18),dp(14),dp(18),dp(14));root.addView(title);
  status=text("Отключено • прямой доступ к сайтам закрыт",15);status.setPadding(dp(16),dp(10),dp(16),dp(6));root.addView(status);
  LinearLayout tabs=new LinearLayout(this);root.addView(tabs); Button config=button("Прокси",tabs),browse=button("Браузер",tabs);
  settings=new LinearLayout(this);settings.setOrientation(LinearLayout.VERTICAL);settings.setPadding(dp(16),dp(4),dp(16),dp(4));root.addView(settings);
  TextView intro=text("Автоматический выбор выхода в США",20);settings.addView(intro);
  detail=text("Прокси США уже встроены и загружены. Нажмите «Подобрать и подключить»: доступность и страна проверяются заново. Публичные серверы ненадёжны и видят адреса сайтов. Это браузер, не VPN для телефона.",14);settings.addView(detail);
  LinearLayout actions=new LinearLayout(this);settings.addView(actions);Button imp=button("Импорт JSON",actions),paste=button("Вставить",actions);
  Button free=new Button(this);free.setText("Обновить список с GitHub");settings.addView(free);free.setOnClickListener(v->confirmPublicSearch());
  connect=new Button(this);connect.setText("Подобрать и подключить");connect.setTextColor(Color.WHITE);connect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));settings.addView(connect);
  LinearLayout extra=new LinearLayout(this);settings.addView(extra);Button off=button("Отключить",extra),clear=button("Удалить",extra);
  list=text("Профилей пока нет",13); ScrollView sv=new ScrollView(this);sv.addView(list);settings.addView(sv,new LinearLayout.LayoutParams(-1,dp(100)));
  LinearLayout nav=new LinearLayout(this);nav.setPadding(dp(10),0,dp(10),0);root.addView(nav);
  address=new EditText(this);address.setText(pending);address.setSingleLine(true);address.setTextSize(14);address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);nav.addView(address,new LinearLayout.LayoutParams(0,dp(48),1));Button go=button("Открыть",nav);
  web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
  WebSettings ws=web.getSettings(); ws.setBlockNetworkLoads(true);ws.setJavaScriptEnabled(true);ws.setDomStorageEnabled(true);ws.setAllowFileAccess(false);ws.setAllowContentAccess(false);ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);ws.setSupportMultipleWindows(false);ws.setMediaPlaybackRequiresUserGesture(true);ws.setCacheMode(WebSettings.LOAD_NO_CACHE);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
  web.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest request){request.deny();}});
  web.setDownloadListener((url,ua,cd,mime,size)->toast("Загрузки во внешние приложения отключены"));
  web.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !allowed(r.getUrl());}
   @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){if(!allowed(r.getUrl()))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));return null;}
   @Override public void onReceivedSslError(WebView v,android.webkit.SslErrorHandler h,android.net.http.SslError e){h.cancel();}
   @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){if(url.startsWith("https://"))address.setText(url);}
   @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame()&&enabled){status.setText("Сайт не загрузился. Проверяем прокси…");if(android.os.SystemClock.elapsedRealtime()-lastScan>15000)scan(false);}}
  });
  imp.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);});
  paste.setOnClickListener(v->{EditText e=new EditText(this);e.setHint("{\"profiles\":[…]}");e.setMinLines(5);e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);new AlertDialog.Builder(this).setTitle("Профили от провайдера").setMessage("JSON может содержать пароли. Импортируйте только профили из доверенного источника.").setView(e).setPositiveButton("Проверить",(d,w)->confirmImport(e.getText().toString())).setNegativeButton("Отмена",null).show();});
  connect.setOnClickListener(v->{if(profiles.isEmpty()&&!loadBundledProfiles())return;enabled=true;scan(true);});off.setOnClickListener(v->disconnect());
  clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Удалить все профили?").setMessage("Пароли и данные браузера будут удалены из приложения.").setPositiveButton("Удалить",(d,w)->{disconnect();profiles.clear();store.clear();CookieManager.getInstance().removeAllCookies(null);web.clearCache(true);web.clearHistory();WebStorage.getInstance().deleteAllData();list.setText("Профилей пока нет");}).setNegativeButton("Отмена",null).show());
  config.setOnClickListener(v->settings.setVisibility(View.VISIBLE));browse.setOnClickListener(v->settings.setVisibility(View.GONE));go.setOnClickListener(v->navigate());address.setOnEditorActionListener((v,id,event)->{navigate();return true;});
  try{profiles=ProfileStore.parse(store.load());}catch(Exception e){status.setText("Не удалось прочитать импортированные профили. Используем встроенный список.");}
  if(profiles.isEmpty())loadBundledProfiles();else renderProfiles();
  if(!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)||!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)){fatal("Обновите Android System WebView: нужны Proxy Override и Document Start Script.");return;}
  try{
   bridge=new ProxyBridge();
   WebViewCompat.addDocumentStartJavaScript(web,"(()=>{for(const k of ['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel','WebTransport']){try{Object.defineProperty(globalThis,k,{value:undefined,writable:false,configurable:false});}catch(e){}}})();",Collections.singleton("*"));
   if(WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)&&WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS))ServiceWorkerControllerCompat.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(true);
   ProxyConfig cfg=new ProxyConfig.Builder().addProxyRule("http://127.0.0.1:"+bridge.getPort()).removeImplicitRules().build();
   ProxyController.getInstance().setProxyOverride(cfg,this::runOnUiThread,()->{if(!closed){ready=true;status.setText("Готово • загружено профилей: "+profiles.size()+" • нажмите «Подобрать и подключить»");}});
  }catch(Exception e){fatal("Не удалось включить локальный прокси. Доступ к сайтам закрыт.");}
 }
 private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(ink);t.setPadding(0,dp(4),0,dp(4));return t;}
 private Button button(String s,LinearLayout row){Button b=new Button(this);b.setText(s);b.setTextSize(12);row.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));return b;}
 private boolean allowed(Uri uri){return "https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null&&(uri.getPort()==-1||uri.getPort()==443)&&uri.getUserInfo()==null;}
 private void navigate(){String s=address.getText().toString().trim();if(!s.contains("://"))s="https://"+s;Uri u=Uri.parse(s);if(!allowed(u)){toast("Только HTTPS-сайты на порту 443");return;}pending=s;if(!ready||selected==null){toast("Сначала подключите проверенный прокси США");return;}settings.setVisibility(View.GONE);web.loadUrl(s);}
 private void fatal(String s){ready=false;connect.setEnabled(false);status.setText(s);}
 private void renderProfiles(){StringBuilder b=new StringBuilder();for(ProxyProfile p:profiles)b.append(p.name).append(" • ").append(p.type.toUpperCase(Locale.ROOT)).append(" • US заявлено\n");list.setText(b.length()==0?"Профилей пока нет":b.toString());}
 private void confirmImport(String json){
  try{List<ProxyProfile> candidate=ProfileStore.parse(json);if(candidate.isEmpty())throw new Exception("Файл не содержит профилей");new AlertDialog.Builder(this).setTitle("Доверяете источнику профилей?").setMessage("Будет заменено профилей: "+candidate.size()+". Прокси видит адреса сайтов. Проверка выхода обращается к ipwho.is через каждый прокси; сервис видит IP прокси. Геолокация — оценка, не гарантия.").setPositiveButton("Импортировать",(d,w)->{try{store.save(json);disconnect();profiles=candidate;renderProfiles();status.setText("Профили сохранены. Нажмите «Подобрать и подключить».");}catch(Exception e){toast("Не удалось сохранить зашифрованные профили");}}).setNegativeButton("Отмена",null).show();}catch(Exception e){toast("Импорт отклонён: "+e.getMessage());}
 }
 @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req==10&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();coordinator.submit(()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>262144)throw new IOException();out.write(b,0,n);}String json=out.toString(StandardCharsets.UTF_8.name());ui.post(()->{if(!closed)confirmImport(json);});}catch(Exception e){ui.post(()->toast("Не удалось прочитать файл (максимум 256 КБ)"));}});}}
 private boolean loadBundledProfiles(){
  try(InputStream in=getAssets().open("us-proxies.txt")){
   profiles=PublicProxySource.loadSnapshot(in);renderProfiles();
   status.setText("Встроенный список загружен • профилей: "+profiles.size()+" • подключение только после проверки US");return true;
  }catch(Exception e){status.setText("Встроенный список не прочитан. Обновите с GitHub или импортируйте свой прокси. Сайты заблокированы.");return false;}
 }
 private void confirmPublicSearch(){
  if(busy){toast("Поиск уже выполняется. Можно отключить и дождаться завершения.");return;}
  new AlertDialog.Builder(this).setTitle("Обновить встроенную выборку?")
   .setMessage("Встроенный список уже доступен без скачивания. Обновление: github.com/proxifly/free-proxy-list (список US). Новый список загружается напрямую через HTTPS с GitHub, при ошибке — jsDelivr: эти сервисы видят ваш обычный IP. Затем проверяется до 60 серверов через ipwho.is. Прокси видит адреса сайтов; не используйте для банков, паролей и личных данных. Скорость и доступность не гарантированы. Будет заменён текущий список в памяти; импортированные профили в хранилище сохранятся.")
   .setPositiveButton("Обновить и подключить",(d,w)->loadPublic()).setNegativeButton("Отмена",null).show();
 }
 private void loadPublic(){
  if(closed||busy)return;if(!ready){toast("Прокси-модуль ещё не готов");return;}
  disconnect();int ticket=generation.get();busy=true;connect.setEnabled(false);status.setText("Загружаем свежий список US с GitHub…");
  coordinator.submit(()->{
   try{List<ProxyProfile> found=PublicProxySource.fetch();
    ui.post(()->{if(closed)return;busy=false;connect.setEnabled(ready);if(ticket!=generation.get())return;profiles=found;renderProfiles();enabled=true;scan(true);});
   }catch(Exception e){ui.post(()->{if(closed)return;busy=false;connect.setEnabled(ready);if(ticket==generation.get())status.setText("Список не загрузился. Повторите поиск или импортируйте свой прокси. Сайты заблокированы.");});}
  });
 }
 private void scan(boolean explicit){
  if(closed||busy||!enabled)return;if(!ready){toast("Прокси-модуль ещё не готов");return;}if(profiles.isEmpty()){toast("Найдите бесплатные прокси или импортируйте свои");return;}
  busy=true;connect.setEnabled(false);lastScan=android.os.SystemClock.elapsedRealtime();int ticket=generation.get();List<ProxyProfile> snapshot=new ArrayList<>(profiles);status.setText("Проверяем US и выбираем минимальную задержку (до 70 секунд)…");
  coordinator.submit(()->{
   ProxySelector.Result outcome=ProxySelector.scan(snapshot,checks,ProxyBridge::probe,()->ticket!=generation.get()||closed,70000);
   ProxyBridge.ProbeResult result=outcome.best;
   ui.post(()->{if(closed)return;busy=false;connect.setEnabled(ready);if(ticket!=generation.get())return;list.setText(outcome.report);
    if(result==null){selected=null;bridge.block();web.stopLoading();web.getSettings().setBlockNetworkLoads(true);status.setText("US-выход не найден за отведённое время • сайты заблокированы. Обновите список с GitHub или повторите проверку позже.");}
    else{boolean changed=selected==null||!selected.id.equals(result.profile.id);selected=result.profile;if(changed)bridge.select(selected);web.getSettings().setBlockNetworkLoads(false);status.setText("US • "+selected.name+" • "+result.ip+" • "+result.latencyMs+" мс");if(explicit)navigate();else if(changed){web.stopLoading();web.loadUrl(pending);}}
    ui.removeCallbacks(health);if(enabled&&foreground)ui.postDelayed(health,180000);
   });
  });
 }
 private void disconnect(){enabled=false;generation.incrementAndGet();selected=null;ui.removeCallbacks(health);if(bridge!=null)bridge.block();if(web!=null){web.stopLoading();web.getSettings().setBlockNetworkLoads(true);web.loadUrl("about:blank");}status.setText("Отключено • прямой доступ к сайтам закрыт");}
 private void toast(String s){if(!closed)Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 @Override protected void onResume(){super.onResume();foreground=true;if(enabled)ui.postDelayed(health,180000);if(web!=null)web.onResume();}
 @Override protected void onPause(){foreground=false;ui.removeCallbacks(health);if(web!=null)web.onPause();super.onPause();}
 @Override public void onBackPressed(){if(web.canGoBack()&&selected!=null)web.goBack();else settings.setVisibility(View.VISIBLE);}
 @Override protected void onDestroy(){closed=true;generation.incrementAndGet();ui.removeCallbacksAndMessages(null);coordinator.shutdownNow();checks.shutdownNow();if(bridge!=null)bridge.close();if(web!=null)web.destroy();super.onDestroy();}
}
