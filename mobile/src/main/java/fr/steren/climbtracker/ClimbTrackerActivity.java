/*
Copyright 2014 Google Inc. All rights reserved.
        Licensed under the Apache License, Version 2.0 (the "License");
        you may not use this file except in compliance with the License.
        You may obtain a copy of the License at
        http://www.apache.org/licenses/LICENSE-2.0
        Unless required by applicable law or agreed to in writing, software
        distributed under the License is distributed on an "AS IS" BASIS,
        WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
        See the License for the specific language governing permissions and
        limitations under the License.
*/
package fr.steren.climbtracker;

import android.Manifest;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.DialogFragment;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.firebase.client.AuthData;
import com.firebase.client.Firebase;
import com.firebase.client.FirebaseError;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.plus.Plus;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import fr.steren.climblib.GradeList;
import fr.steren.climblib.Path;


/**
 * An activity representing a list of ClimbSessions. This activity
 * has different presentations for handset and tablet-size devices. On
 * handsets, the activity presents a list of items, which when touched,
 * lead to a {@link ClimbSessionDetailActivity} representing
 * item details. On tablets, the activity presents the list of items and
 * item details side-by-side using two vertical panes.
 * <p/>
 * The activity makes heavy use of fragments. The list of items is a
 * {@link ClimbSessionListFragment} and the item details
 * (if present) is a {@link ClimbSessionDetailFragment}.
 * <p/>
 * This activity also implements the required
 * {@link ClimbSessionListFragment.Callbacks} interface
 * to listen for item selections.
 */
public class ClimbTrackerActivity extends AppCompatActivity
        implements ClimbSessionListFragment.Callbacks, GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        SharedPreferences.OnSharedPreferenceChangeListener,
        GradePickerFragment.GradeDialogListener {

    private static final String LOG_TAG = ClimbTrackerActivity.class.getSimpleName();

    private static final int PERMISSION_UPFRONT_REQUEST = 123;

    /**
     * Whether or not the activity is in two-pane mode, i.e. running on a tablet
     * device.
     */
    private boolean mTwoPane;

    private Firebase firebaseRef;

    /** reference to the potential last added climb */
    private Firebase mNewClimbRef;

    /* Client used to interact with Google APIs. */
    private GoogleApiClient mGoogleAuthApiClient;

    /* Client used to interact with Wear APIs. */
    private GoogleApiClient mGoogleApiClient;

    /* Data from the authenticated user */
    private AuthData mAuthData;

    /* A flag indicating that a PendingIntent is in progress and prevents
     * us from starting further intents.
     */
    private boolean mIntentInProgress;

    /* Request code used to invoke sign in user interactions. */
    private static final int RC_SIGN_IN = 0;

    /** Analytics tracker */
    private Tracker mTracker;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_climb_tracker);

        // Analytics.
        ClimbTrackerApplication application = (ClimbTrackerApplication) getApplication();
        mTracker = application.getDefaultTracker();

        // track pageview
        mTracker.setScreenName(LOG_TAG);
        mTracker.send(new HitBuilders.ScreenViewBuilder().build());

        Firebase.setAndroidContext(this);
        firebaseRef = new Firebase(getResources().getString(R.string.firebase_url));

        mGoogleAuthApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Plus.API)
                .addScope(new Scope(Scopes.PROFILE))
                .build();

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addApi(LocationServices.API)
                .build();
        mGoogleApiClient.connect();


        /* Check if the user is authenticated with Firebase already. If this is the case we can set the authenticated
         * user and hide hide any login buttons */
        firebaseRef.addAuthStateListener(new Firebase.AuthStateListener() {
            @Override
            public void onAuthStateChanged(AuthData authData) {
                setAuthenticatedUser(authData);
            }
        });

        if (findViewById(R.id.climbsession_detail_container) != null) {
            // The detail container view will be present only in the
            // large-screen layouts (res/values-large and
            // res/values-sw600dp). If this view is present, then the
            // activity should be in two-pane mode.
            mTwoPane = true;

            // In two-pane mode, list items should be given the
            // 'activated' state when touched.
            ((ClimbSessionListFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.climbsession_list))
                    .setActivateOnItemClick(true);
        }

        initFAB();

        initPreferences();

        boolean needPermission = checkAndRequestPermissions();

        AuthData authData = firebaseRef.getAuth();
        if(!needPermission && authData == null) {
            mGoogleAuthApiClient.connect();
        }
    }

    private void initPreferences() {
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(this);

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        String gradeSystemTypePref = sharedPref.getString(Path.PREF_GRAD_SYSTEM_TYPE, GradeList.SYSTEM_DEFAULT);
        sendGradeSystemToWear(gradeSystemTypePref);
    }

    private void initFAB() {
        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DialogFragment gradePickerFragment = new GradePickerFragment();
                gradePickerFragment.show(getSupportFragmentManager(), "gradePicker");
            }
        });
    }

    /**
     * Check for required and optional permissions and request them if needed
     * @return true if there are permissions to ask, false otherwise
     */
    private boolean checkAndRequestPermissions() {
        ArrayList<String> permissionsToAsk = new ArrayList<String>();

        // Check for account permission, request it if not granted.
        // it seems that we cannot simply use Manifest.permission.USE_CREDENTIALS ?
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToAsk.add(Manifest.permission.GET_ACCOUNTS);
        }

        // Request for location permission if not already granted and not already asked
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // We are cool with this one, we do not ask if the user already declined
            if(!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                permissionsToAsk.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
        }

        if(!permissionsToAsk.isEmpty()) {
            String[] permissionsToAskArray = permissionsToAsk.toArray(new String[permissionsToAsk.size()]);
            ActivityCompat.requestPermissions(this, permissionsToAskArray, PERMISSION_UPFRONT_REQUEST);
            return true;
        } else {
            return false;
        }


    }

    private void sendGradeSystemToWear(String system) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(Path.GRADE_SYSTEM);
        putDataMapReq.getDataMap().putString(Path.GRADE_SYSTEM_KEY, system);
        PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        Wearable.DataApi.putDataItem(mGoogleApiClient, putDataReq);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            Intent intent = new Intent(ClimbTrackerActivity.this, SettingsActivity.class);
            startActivity(intent);
        } else if (id == R.id.action_disconnect) {
            firebaseRef.unauth();
            mGoogleAuthApiClient.disconnect();
            mGoogleAuthApiClient.connect();
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Once a user is logged in, take the mAuthData provided from Firebase and "use" it.
     */
    private void setAuthenticatedUser(AuthData authData) {
        this.mAuthData = authData;
    }

    public void onConnectionFailed(ConnectionResult result) {
        if (!mIntentInProgress && result.hasResolution()) {
            try {
                mIntentInProgress = true;
                startIntentSenderForResult(result.getResolution().getIntentSender(), RC_SIGN_IN, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                // The intent was canceled before it was sent.  Return to the default
                // state and attempt to connect to get an updated ConnectionResult.
                mIntentInProgress = false;
                mGoogleAuthApiClient.connect();
            }
        }
    }

    protected void onActivityResult(int requestCode, int responseCode, Intent intent) {
        if (requestCode == RC_SIGN_IN) {
            mIntentInProgress = false;
            if (!mGoogleAuthApiClient.isConnecting()) {
                mGoogleAuthApiClient.connect();
            }
        }
    }

    private void getGoogleOAuthTokenAndLogin() {
        /* Get OAuth token in Background */
        AsyncTask<Void, Void, String> task = new AsyncTask<Void, Void, String>() {
            String errorMessage = null;

            @Override
            protected String doInBackground(Void... params) {
                String token = null;

                try {
                    String scope = String.format("oauth2:%s", Scopes.PROFILE);
                    token = GoogleAuthUtil.getToken(ClimbTrackerActivity.this, Plus.AccountApi.getAccountName(mGoogleAuthApiClient), scope);
                } catch (IOException transientEx) {
                    /* Network or server error */
                    Log.e(LOG_TAG, "Error authenticating with Google: " + transientEx);
                    errorMessage = "Network error: " + transientEx.getMessage();
                } catch (UserRecoverableAuthException e) {
                    Log.w(LOG_TAG, "Recoverable Google OAuth error: " + e.toString());
                    /* We probably need to ask for permissions, so start the intent if there is none pending */
                    mGoogleAuthApiClient.connect();
                } catch (GoogleAuthException authEx) {
                    /* The call is not ever expected to succeed assuming you have already verified that
                     * Google Play services is installed. */
                    Log.e(LOG_TAG, "Error authenticating with Google: " + authEx.getMessage(), authEx);
                    errorMessage = "Error authenticating with Google: " + authEx.getMessage();
                }

                return token;
            }

            @Override
            protected void onPostExecute(String token) {
                // Authenticate
                // see https://www.firebase.com/docs/android/guide/login/google.html
                firebaseRef.authWithOAuthToken("google", token, new Firebase.AuthResultHandler() {
                    @Override
                    public void onAuthenticated(AuthData authData) {
                        // the Google user is now authenticated with Firebase
                        setAuthenticatedUser(authData);
                    }

                    @Override
                    public void onAuthenticationError(FirebaseError firebaseError) {
                        Toast.makeText(ClimbTrackerActivity.this, firebaseError.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        };
        task.execute();
    }

    public void onConnected(Bundle connectionHint) {
        getGoogleOAuthTokenAndLogin();
    }

    public void onConnectionSuspended(int cause) {
        mGoogleAuthApiClient.connect();
    }


    /**
     * Callback method from {@link ClimbSessionListFragment.Callbacks}
     * indicating that the item was selected.
     */
    @Override
    public void onItemSelected(ClimbSession session) {
        if (mTwoPane) {
            // In two-pane mode, show the detail view in this activity by
            // adding or replacing the detail fragment using a
            // fragment transaction.
            Bundle arguments = new Bundle();
            arguments.putLong(ClimbSessionDetailFragment.ARG_FIRST_CLIMB_TIME, session.getFirstClimbDate().getTime());
            arguments.putLong(ClimbSessionDetailFragment.ARG_LAST_CLIMB_TIME, session.getLastClimbDate().getTime());
            ClimbSessionDetailFragment fragment = new ClimbSessionDetailFragment();
            fragment.setArguments(arguments);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.climbsession_detail_container, fragment)
                    .commit();

        } else {
            // In single-pane mode, simply start the detail activity
            // for the selected item ID.
            Intent detailIntent = new Intent(this, ClimbSessionDetailActivity.class);
            detailIntent.putExtra(ClimbSessionDetailFragment.ARG_FIRST_CLIMB_TIME, session.getFirstClimbDate().getTime());
            detailIntent.putExtra(ClimbSessionDetailFragment.ARG_LAST_CLIMB_TIME, session.getLastClimbDate().getTime());
            startActivity(detailIntent);
        }
    }


    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(Path.PREF_GRAD_SYSTEM_TYPE)) {
            mTracker.send(new HitBuilders.EventBuilder()
                    .setCategory("settings")
                    .setAction("changed-system")
                    .build());

            String gradeSystemTypePref = sharedPreferences.getString(Path.PREF_GRAD_SYSTEM_TYPE, GradeList.SYSTEM_DEFAULT);
            sendGradeSystemToWear(gradeSystemTypePref);
        }
    }

    @Override
    public void onGradeSelected(String grade) {
        mTracker.send(new HitBuilders.EventBuilder()
                .setCategory("action")
                .setAction("save-climb")
                .build());

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        String gradeSystemType = sharedPref.getString(Path.PREF_GRAD_SYSTEM_TYPE, GradeList.SYSTEM_DEFAULT);

        double latitude = 0;
        double longitude = 0;

        if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Location lastLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
            if(lastLocation != null) {
                latitude = lastLocation.getLatitude();
                longitude = lastLocation.getLongitude();
            }
        }

        Climb newClimb = new Climb(new Date(), grade, gradeSystemType, latitude, longitude);
        mNewClimbRef = firebaseRef.child("users")
                .child(mAuthData.getUid())
                .child("climbs")
                .push();
        mNewClimbRef.setValue(newClimb);

        final CoordinatorLayout coordinatorLayout = (CoordinatorLayout) findViewById(R.id.mainLayout);
        Snackbar.make(coordinatorLayout, R.string.climb_confirmation, Snackbar.LENGTH_LONG).setAction(R.string.climb_save_undo, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mNewClimbRef.removeValue();
            }
        }).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_UPFRONT_REQUEST: {
                // first check if we can login
                if(ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED) {

                    // if we can, check if we can see the location, if not, display a message.
                    for(int i = 0; i < grantResults.length; i++ ) {
                        if(permissions[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION) && grantResults[i] == PackageManager.PERMISSION_DENIED ) {
                            // no problem, just say we are not going to store location
                            final CoordinatorLayout coordinatorLayout = (CoordinatorLayout) findViewById(R.id.mainLayout);
                            Snackbar.make(coordinatorLayout, R.string.location_permission_denied, Snackbar.LENGTH_LONG).show();
                        }
                    }
                    mGoogleAuthApiClient.connect();
                } else {
                    // We need this permission to log in, explain and ask again
                    final CoordinatorLayout coordinatorLayout = (CoordinatorLayout) findViewById(R.id.mainLayout);
                    Snackbar.make(coordinatorLayout, R.string.account_permission_denied, Snackbar.LENGTH_INDEFINITE)
                    .setAction(android.R.string.ok, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // Request the permission again.
                            ActivityCompat.requestPermissions(ClimbTrackerActivity.this,
                                    new String[]{Manifest.permission.GET_ACCOUNTS},
                                    PERMISSION_UPFRONT_REQUEST);
                        }
                    })
                    .show();
                }
            }

        }
    }
}
