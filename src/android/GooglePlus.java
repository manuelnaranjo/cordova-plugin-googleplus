package nl.xservices.plugins;

import java.io.IOException;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.plus.Plus;
import com.google.android.gms.plus.PlusShare;
import com.google.android.gms.plus.PlusShare.Builder;
import com.google.android.gms.plus.model.people.Person;

import org.apache.cordova.*;
import org.json.JSONException;
import org.json.JSONObject;

public class GooglePlus extends CordovaPlugin implements ConnectionCallbacks,
    OnConnectionFailedListener {

  public static final String ACTION_LOGIN             = "login";
  public static final String ACTION_TRY_SILENT_LOGIN  = "trySilentLogin";
  public static final String ACTION_LOGOUT            = "logout";
  public static final String ACTION_DISCONNECT        = "disconnect";
  public static final String ACTION_SHARE             = "share";

  public static final String ARGUMENT_ANDROID_KEY     = "androidApiKey";
  public static final String ARGUMENT_WEB_KEY         = "webApiKey";

  public static final int    REQUEST_SHARE            = 0x1000;
  public static final int    REQUEST_UPGRADE          = 0x2000;
  public static final int    REQUEST_USER_RECOVERABLE = 0x3000;
  private static final int   REQUEST_SIGNIN           = 0x4000;

  // Wraps our service connection to Google Play services and provides access to
  // the users sign in state and Google APIs
  private GoogleApiClient    mGoogleApiClient;
  private String             apiKey, webKey;
  private CallbackContext    savedCallbackContext;
  private Activity           mActivity;
  private boolean            trySilentLogin;
  private boolean            loggingOut;

  @Override
  public void initialize(CordovaInterface cordova, CordovaWebView webView) {
    super.initialize(cordova, webView);
    mGoogleApiClient = buildGoogleApiClient();
    this.mActivity = cordova.getActivity();
  }

  @Override
  public boolean execute(String action, CordovaArgs args,
      CallbackContext callbackContext) throws JSONException {
    this.savedCallbackContext = callbackContext;

    if (args.optJSONObject(0) != null) {
      JSONObject obj = args.getJSONObject(0);
      System.out.println(obj);
      this.webKey = obj.optString(ARGUMENT_WEB_KEY, null);
      this.apiKey = obj.optString(ARGUMENT_ANDROID_KEY, null);
    }

    if (ACTION_LOGIN.equals(action)) {
      this.trySilentLogin = false;
      mGoogleApiClient.connect();

    }
    else if (ACTION_TRY_SILENT_LOGIN.equals(action)) {
      this.trySilentLogin = true;
      mGoogleApiClient.connect();

    }
    else if (ACTION_LOGOUT.equals(action)) {
      try {
        Plus.AccountApi.clearDefaultAccount(mGoogleApiClient);
        mGoogleApiClient.disconnect();
        // needed in onActivityResult when the connect method below comes back
        loggingOut = true;
        mGoogleApiClient = buildGoogleApiClient();
        mGoogleApiClient.connect();
      } catch (IllegalStateException e) {

      }
      savedCallbackContext.success("logged out");

    }
    else if (ACTION_DISCONNECT.equals(action)) {
      disconnect();
    }
    else if (ACTION_SHARE.equals(action)) {
      share(args);
    }
    return true;
  }

  private void disconnect() {
    try {
      Plus.AccountApi.revokeAccessAndDisconnect(mGoogleApiClient)
          .setResultCallback(new ResultCallback<Status>() {
            @Override
            public void onResult(Status status) {
              // mGoogleApiClient is now disconnected and access has been
              // revoked.
              // Don't care if it was disconnected already (status != success).
              mGoogleApiClient = buildGoogleApiClient();
              savedCallbackContext.success("disconnected");
            }
          });
    } catch (IllegalStateException e) {
      savedCallbackContext.success("disconnected");
    }
  }

  private GoogleApiClient buildGoogleApiClient() {
    return new GoogleApiClient.Builder(webView.getContext())
        .addConnectionCallbacks(this).addOnConnectionFailedListener(this)
        .addApi(Plus.API, Plus.PlusOptions.builder().build())
        .addScope(Plus.SCOPE_PLUS_LOGIN).addScope(Plus.SCOPE_PLUS_PROFILE)
        .build();
  }

  private void resolveToken(final String email, final JSONObject result) {
    final Context context = mActivity.getApplicationContext();

    cordova.getThreadPool().execute(new Runnable() {
      public void run() {
        String scope = null;
        String token = null;

        try {
          if (GooglePlus.this.webKey != null) {
            // Retrieve server side tokens
            scope = "oauth2:server:client_id:" + GooglePlus.this.webKey;
            scope+=":api_scope:openid email profile";
            try {
              GoogleAuthUtil.clearToken(context, scope);
            } catch (Exception e) {
              System.err.println("Failed cleanig up credentials " +
                                 e.getMessage());
            }

            token = GoogleAuthUtil.getToken(context, email, scope);
            result.put("idToken", token);
          }
          else if (GooglePlus.this.apiKey != null) {
            // Retrieve the oauth token with offline mode
            scope = "oauth2:server:client_id:" + GooglePlus.this.apiKey;
            scope += ":api_scope:" + Scopes.PLUS_LOGIN;
            token = GoogleAuthUtil.getToken(context, email, scope);
            result.put("oauthToken", token);
          }
          else {
            // Retrieve the oauth token with offline mode
            scope = "oauth2:" + Scopes.PLUS_LOGIN;
            token = GoogleAuthUtil.getToken(context, email, scope);
            result.put("oauthToken", token);
          }
        } catch (UserRecoverableAuthException userAuthEx) {
          // Start the user recoverable action using the intent returned by
          // getIntent()
          mActivity.startActivityForResult(userAuthEx.getIntent(), REQUEST_USER_RECOVERABLE);
          return;
        } catch (IOException e) {
          savedCallbackContext.error("Failed to retrieve token: "
              + e.getMessage());
          return;
        } catch (GoogleAuthException e) {
          savedCallbackContext.error("Failed to retrieve token: "
              + e.getMessage());
          return;
        } catch (JSONException e) {
          savedCallbackContext.error("Failed to retrieve token: "
              + e.getMessage());
          return;
        }

        savedCallbackContext.success(result);
      }
    });
  }

  /**
   * onConnected is called when our Activity successfully connects to Google
   * Play services. onConnected indicates that an account was selected on the
   * device, that the selected account has granted any requested permissions to
   * our app and that we were able to establish a service connection to Google
   * Play services.
   */
  @Override
  public void onConnected(Bundle connectionHint) {
    final String email = Plus.AccountApi.getAccountName(mGoogleApiClient);
    final Person user = Plus.PeopleApi.getCurrentPerson(mGoogleApiClient);

    final JSONObject result = new JSONObject();
    try {
      result.put("email", email);
      // in case there was no internet connection, this may be null
      if (user != null) {
        result.put("userId", user.getId());
        result.put("displayName", user.getDisplayName());
        result.put("gender", getGender(user.getGender()));
        if (user.getImage() != null) {
          result.put("imageUrl", user.getImage().getUrl());
        }
        if (user.getName() != null) {
          result.put("givenName", user.getName().getGivenName());
          result.put("middleName", user.getName().getMiddleName());
          result.put("familyName", user.getName().getFamilyName());
          if (user.hasAgeRange()) {
            if (user.getAgeRange().hasMin()) {
              result.put("ageRangeMin", user.getAgeRange().getMin());
            }
            if (user.getAgeRange().hasMax()) {
              result.put("ageRangeMax", user.getAgeRange().getMax());
            }
          }
          if (user.hasBirthday()) {
            result.put("birthday", user.getBirthday());
          }
        }
      }
      resolveToken(email, result);
    } catch (JSONException e) {
      savedCallbackContext.error("result parsing trouble, error: "
          + e.getMessage());
    }
  }

  // same as iOS values
  private static String getGender(int gender) {
    switch (gender)
    {
      case 0 :
        return "male";
      case 1 :
        return "female";
      default :
        return "other";
    }
  }

  @Override
  public void onConnectionSuspended(int constantInClass_ConnectionCallbacks) {
    this.savedCallbackContext.error("connection trouble, code: "
        + constantInClass_ConnectionCallbacks);
  }

  /**
   * onConnectionFailed is called when our Activity could not connect to Google
   * Play services. onConnectionFailed indicates that the user needs to select
   * an account, grant permissions or resolve an error in order to sign in.
   */
  @Override
  public void onConnectionFailed(ConnectionResult result) {
    if (result.getErrorCode() == ConnectionResult.SERVICE_MISSING) { // e.g.
                                                                     // emulator
                                                                     // without
                                                                     // play
                                                                     // services
                                                                     // installed
      this.savedCallbackContext.error("service not available");
    }
    else if (loggingOut) {
      loggingOut = false;
      this.savedCallbackContext.success("logged out");
    }
    else if (result.getErrorCode() == ConnectionResult.SIGN_IN_REQUIRED
        && !trySilentLogin) {
      final PendingIntent mSignInIntent = result.getResolution();
      try {
        // startIntentSenderForResult is started from the CordovaActivity,
        // set callback to this plugin to make sure this.onActivityResult gets
        // called afterwards
        ((CordovaActivity) mActivity).setActivityResultCallback(this);
        mActivity.startIntentSenderForResult(mSignInIntent.getIntentSender(), REQUEST_SIGNIN, 
            null, 0, 0, 0);
      } catch (IntentSender.SendIntentException ignore) {
        mGoogleApiClient.connect();
      }
    }
    else {
      this.savedCallbackContext.error("no valid token");
    }
  }

  @Override
  public void onActivityResult(int requestCode, final int resultCode,
      final Intent intent) {

    super.onActivityResult(requestCode, resultCode, intent);

    switch (requestCode)
    {
      case REQUEST_SHARE :
      {
        if (resultCode == Activity.RESULT_OK) {
          this.savedCallbackContext.success();
        }
        else {
          this.savedCallbackContext.error(resultCode);
        }
        return;
      }
      case REQUEST_USER_RECOVERABLE :
      case REQUEST_SIGNIN :
      {
        
        if (resultCode == Activity.RESULT_OK) {
          mGoogleApiClient.connect();
        }
        else {
          this.savedCallbackContext.error("user cancelled");
        }
        
        return;
      }
    }
    
    System.out.println("Invalid requestCode " + requestCode);

  }

  private void share(CordovaArgs args) throws JSONException {
    JSONObject config = args.optJSONObject(0);

    Builder builder = new PlusShare.Builder(mActivity).setType("text/plain");

    if (config.optString("text") != null) {
      builder.setText(config.getString("text"));
    }

    if (config.optString("url") != null) {
      builder.setContentUrl(Uri.parse(config.getString("url")));
    }

    mActivity.startActivityForResult(builder.getIntent(), REQUEST_SHARE);
  }
}
