package dev.pablo.eyeseeyou;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final int REQ_LOCATION = 40;

    private static final String WS_URL = "ws://192.168.0.14:6666/ws";

    private GoogleMap map;
    private FusedLocationProviderClient fused;
    private LocationCallback callback;

    private TextView wsStatus;
    private MaterialButton btnCenter;

    private LatLng lastLatLng;

    private WsManager wsManager;
    private ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback netCb = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(@NonNull android.net.Network network) {
            runOnUiThread(() -> {
                if (wsManager != null) {
                    wsManager.forceReconnect();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        wsStatus = findViewById(R.id.wsStatus);
        btnCenter = findViewById(R.id.btnCenter);

        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);


        fused = LocationServices.getFusedLocationProviderClient(this);


        wsManager = new WsManager(WS_URL, new WsManager.Listener() {
            @Override public void onStatus(String s) { wsStatus.setText(s); }
        });

        btnCenter.setOnClickListener(v -> {
            if (map != null && lastLatLng != null) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(lastLatLng, 17f));
            }
        });

        ensureLocationPermissions();


        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        connectivityManager.registerDefaultNetworkCallback(netCb);
    }

    @Override protected void onStart() {
        super.onStart();
        if (wsManager != null) wsManager.connect();
    }

    @Override protected void onStop() {
        super.onStop();
        if (wsManager != null) wsManager.close();
    }

    private void sendLocationOverWs(Location loc) {
        if (wsManager == null) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("lat", loc.getLatitude());
            payload.put("lng", loc.getLongitude());
            payload.put("accuracy", loc.getAccuracy());
            payload.put("provider", loc.getProvider());
            payload.put("timestamp", System.currentTimeMillis());
            wsManager.send(payload.toString());
        } catch (Exception ignored) {}
    }

    private void ensureLocationPermissions() {
        boolean fine =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
        boolean coarse =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;

        if (fine || coarse) {
            startLocationUpdates();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQ_LOCATION
            );
        }
    }

    private void startLocationUpdates() {
        LocationRequest req = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                30 * 60 * 1000L
        )
                .setMinUpdateIntervalMillis(5 * 60 * 1000L)
                .setWaitForAccurateLocation(true)
                .build();

        callback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult lr) {
                Location loc = lr.getLastLocation();
                if (loc == null) return;
                lastLatLng = new LatLng(loc.getLatitude(), loc.getLongitude());
                updateMapMarker(lastLatLng);
                sendLocationOverWs(loc); // <-- aquí mandamos
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        fused.requestLocationUpdates(req, callback, getMainLooper());

        fused.getLastLocation().addOnSuccessListener(loc -> {
            if (loc != null) {
                lastLatLng = new LatLng(loc.getLatitude(), loc.getLongitude());
                updateMapMarker(lastLatLng);
                sendLocationOverWs(loc);
            }
        });

        com.google.android.gms.tasks.CancellationTokenSource cts =
                new com.google.android.gms.tasks.CancellationTokenSource();
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(loc -> {
                    if (loc != null) {
                        lastLatLng = new LatLng(loc.getLatitude(), loc.getLongitude());
                        updateMapMarker(lastLatLng);
                        sendLocationOverWs(loc);
                    }
                });
    }


    private void updateMapMarker(LatLng latLng) {
        if (map == null) return;
        map.clear();
        map.addMarker(new MarkerOptions()
                .position(latLng)
                .title("Estás aquí")
                .snippet("Compartiendo tu ubicación a Rototec")
                .icon(BitmapDescriptorFactory.defaultMarker(200f))
        );
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16.5f));
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        this.map = googleMap;
        map.getUiSettings().setZoomControlsEnabled(true);
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setMapToolbarEnabled(false);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map.setMyLocationEnabled(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(requestCode, perms, grants);
        if (requestCode == REQ_LOCATION) {
            boolean granted = false;
            for (int g : grants) if (g == PackageManager.PERMISSION_GRANTED) granted = true;
            if (granted) {
                startLocationUpdates();
                if (map != null) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        map.setMyLocationEnabled(true);
                    }
                }
            } else {
                Toast.makeText(this, "Se requieren permisos de ubicación", Toast.LENGTH_LONG).show();
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.fromParts("package", getPackageName(), null));
                startActivity(i);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (callback != null) fused.removeLocationUpdates(callback);
        if (wsManager != null) wsManager.shutdown();
        if (connectivityManager != null) {
            try { connectivityManager.unregisterNetworkCallback(netCb); } catch (Exception ignored) {}
        }
    }

    static class WsManager {
        interface Listener { void onStatus(String s); }

        private final String url;
        private final Listener listener;
        private final Handler handler = new Handler(Looper.getMainLooper());

        private OkHttpClient client;
        private WebSocket ws;
        private boolean manualClose = false;
        private int retry = 0;
        private boolean isOpen = false;

        private final java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();

        WsManager(String url, Listener listener) {
            this.url = url;
            this.listener = listener;
            this.client = new OkHttpClient.Builder()
                    .retryOnConnectionFailure(true)
                    .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
        }

        void connect() {
            manualClose = false;
            isOpen = false;
            post("Conectando al servidor...");
            Request req = new Request.Builder().url(url).build();
            ws = client.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                    retry = 0;
                    isOpen = true;
                    post("Conectado al servidor");
                    // Drenar cola
                    while (!pending.isEmpty()) {
                        String msg = pending.pollFirst();
                        if (msg != null) ws.send(msg);
                    }
                }

                @Override public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                    isOpen = false;
                    post("Desconectado del servidor (" + code + ")");
                    if (!manualClose) scheduleReconnect();
                }

                @Override public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response r) {
                    isOpen = false;
                    post("Error conectandose al servidor " + t.getMessage());
                    if (!manualClose) scheduleReconnect();
                }
            });
        }

        void forceReconnect() {
            close();
            handler.postDelayed(this::connect, 500);
        }

        void close() {
            manualClose = true;
            isOpen = false;
            if (ws != null) {
                try { ws.close(1000, "bye"); } catch (Exception ignored) {}
                ws = null;
            }
        }

        void shutdown() {
            close();
            try { client.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
            try { client.connectionPool().evictAll(); } catch (Exception ignored) {}
        }

        boolean send(String text) {
            if (isOpen && ws != null) {
                return ws.send(text);
            } else {
                if (pending.size() > 100) pending.pollFirst();
                pending.offerLast(text);
                post("Servidor en cola (" + pending.size() + ")");
                return false;
            }
        }

        private void scheduleReconnect() {
            long delay = (long) Math.min(30, Math.pow(2, Math.max(0, retry)));
            retry = Math.min(retry + 1, 6);
            post("Reconectando al servidor en:" + delay + "s…");
            handler.postDelayed(this::connect, delay * 1000L);
        }

        private void post(String s) { if (listener != null) listener.onStatus(s); }
    }

}
