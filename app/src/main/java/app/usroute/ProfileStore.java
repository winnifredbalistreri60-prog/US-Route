package app.usroute;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public final class ProfileStore {
 private static final String ALIAS="usroute.profiles.v1";
 private final Context context;
 public ProfileStore(Context c){ context=c; }
 private javax.crypto.SecretKey key() throws Exception {
  KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
  if(!ks.containsAlias(ALIAS)){
   KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
   g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); g.generateKey();
  }
  return (javax.crypto.SecretKey)ks.getKey(ALIAS,null);
 }
 public void save(String json) throws Exception {
  Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key());
  String blob=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(json.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
  if(!context.getSharedPreferences("vault",0).edit().putString("profiles",blob).commit()) throw new Exception("save");
 }
 public String load() throws Exception {
  String s=context.getSharedPreferences("vault",0).getString("profiles",null); if(s==null)return "{\"profiles\":[]}";
  String[] parts=s.split(":"); if(parts.length!=2)throw new Exception("vault");
  Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
  return new String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
 }
 public void clear(){context.getSharedPreferences("vault",0).edit().clear().commit();}
 public static List<ProxyProfile> parse(String json) throws Exception {
  if(json.getBytes(StandardCharsets.UTF_8).length>262144)throw new Exception("Файл больше 256 КБ");
  JSONArray a=new JSONObject(json).getJSONArray("profiles"); if(a.length()>20)throw new Exception("Не больше 20 профилей");
  List<ProxyProfile> result=new ArrayList<>(); Set<String> ids=new HashSet<>();
  for(int i=0;i<a.length();i++){
   JSONObject p=a.getJSONObject(i); String id=p.optString("id","p"+i), name=p.optString("name","Прокси "+(i+1)),type=p.getString("type").toLowerCase(Locale.ROOT),host=p.getString("host").trim(),user=p.optString("username",""),pass=p.optString("password",""); int port=p.getInt("port");
   if(!Arrays.asList("http","https","socks5").contains(type))throw new Exception("Поддерживаются http, https, socks5");
   if(!p.optString("country","").equalsIgnoreCase("US"))throw new Exception("Нужны профили country: US");
   if(!host.matches("[A-Za-z0-9.-]{1,253}")||host.startsWith(".")||host.endsWith(".")||host.contains(".."))throw new Exception("Неверный адрес сервера: нужен DNS или IPv4");
   if(port<1||port>65535||id.isEmpty()||id.length()>80||!ids.add(id)||name.length()>80)throw new Exception("Неверный порт, имя или повтор id");
   if(user.length()>255||pass.length()>255||user.contains(":")||user.matches("(?s).*[\\r\\n\\x00].*")||pass.matches("(?s).*[\\r\\n\\x00].*"))throw new Exception("Неверные поля авторизации");
   if(user.isEmpty()&&!pass.isEmpty())throw new Exception("Для пароля нужен username");
   result.add(new ProxyProfile(id,name,type,host,port,user,pass));
  }
  return result;
 }
}
