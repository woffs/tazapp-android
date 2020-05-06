package de.thecode.android.tazreader.sync;

import android.content.Context;
import android.content.SharedPreferences.Editor;

import de.thecode.android.tazreader.data.TazSettings;
import de.thecode.android.tazreader.secure.Installation;

import timber.log.Timber;

public class AccountHelper {

    public static final String ACCOUNT_DEMO_USER = "demo";
    public static final String ACCOUNT_DEMO_PASS = "demo";

    private static AccountHelper instance;

    public static synchronized AccountHelper getInstance(Context context) {
        if (instance == null) instance = new AccountHelper(context.getApplicationContext());
        return instance;
    }


    private TazSettings preferences;
    private String      cipherPassword;

    private AccountHelper(Context context) {
        preferences = TazSettings.getInstance(context);
        cipherPassword = Installation.id(context);
    }

    public String getUser(String defValue) {
        return preferences.getDecrytedPrefString(cipherPassword, TazSettings.PREFKEY.USER, defValue);
    }

    public String getPassword(String defValue) {
        return preferences.getDecrytedPrefString(cipherPassword, TazSettings.PREFKEY.PASS, defValue);
    }

    public void setUser(String user, String password) {
        preferences.setEncrytedPrefString(cipherPassword, TazSettings.PREFKEY.USER, user);
        preferences.setEncrytedPrefString(cipherPassword, TazSettings.PREFKEY.PASS, password);
//        setAuthenticated(true);
    }

    public boolean isDemo() {
        return getUser(ACCOUNT_DEMO_USER).equals(ACCOUNT_DEMO_USER);
    }

    public void removeUser() {
        preferences.removePref(TazSettings.PREFKEY.USER);
        preferences.removePref(TazSettings.PREFKEY.PASS);
//        setAuthenticated(false);
    }

//    public void setAuthenticated(boolean isAuthenticated) {
//        preferences.setPref(TazSettings.PREFKEY.AUTHENTICATED,isAuthenticated);
//    }
//
//    public boolean isAuthenticated(){
//       return preferences.getPrefBoolean(TazSettings.PREFKEY.AUTHENTICATED,false);
//    }
}
