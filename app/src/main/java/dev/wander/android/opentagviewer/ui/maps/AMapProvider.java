package dev.wander.android.opentagviewer.ui.maps;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.UiSettings;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import com.amap.api.maps.CameraUpdate;

/**
 * 高德地图提供商实现（唯一实现）。
 * <br>
 * <b>Direct SDK calls, not reflection.</b> This class used to reach every AMap type through
 * {@code Class.forName} and {@code getMethod}, because the SDK was optional at build time. It
 * no longer is - {@code 3dmap-location-search} is a normal dependency and this is the only
 * provider left - so the reflection bought nothing and cost three things:
 * <ul>
 *   <li><b>Speed.</b> A marker with an icon performed a dozen lookups, each scanning the whole
 *       method table. Dragging the tag carousel redraws markers, and that ran on the main
 *       thread.</li>
 *   <li><b>Silence.</b> A renamed method became a caught exception and a marker that quietly
 *       never appeared, so a broken SDK upgrade looked like "the map is empty".</li>
 *   <li><b>Reachability.</b> R8 cannot see reflective calls, so it had to keep far more of the
 *       SDK than was used, and the shrinker could not verify a single one of them.</li>
 * </ul>
 * Everything here is now checked by javac, runs at direct-call speed, and shrinks with the app.
 * <br>
 * Coordinate handling is unchanged: the app stores WGS-84 and AMap draws GCJ-02, so every
 * point crossing this boundary is converted, in both directions.
 */
public class AMapProvider implements IMapProvider {
    private static final String TAG = "AMapProvider";

    /** AMap draws its logo bottom-left; keep it clear of the tag carousel. */
    private static final int LOGO_BOTTOM_MARGIN_DP = 96;

    private Activity activity;
    private OnMapReadyCallback callback;

    private TextureMapView mapView;
    private AMap aMap;
    private UiSettings uiSettings;

    private final Map<String, Marker> markers = new HashMap<>();
    private final Map<Marker, String> markerIds = new IdentityHashMap<>();
    private final Map<String, Polyline> polylines = new HashMap<>();

    private OnMapClickListener onMapClickListener;
    private OnMarkerClickListener onMarkerClickListener;

    private MapStyle currentMapStyle = MapStyle.FOLLOW_SYSTEM;

    /**
     * Set once the SDK view has been torn down.
     * <br>
     * Every mutating call checks it. A late callback - a fetch finishing after the user pressed
     * back - would otherwise add a marker to a destroyed TextureMapView, and AMap answers that
     * with a native abort rather than a Java exception, so it cannot be caught.
     */
    private volatile boolean destroyed = false;

    @Override
    public void initialize(Activity activity, int containerViewId, OnMapReadyCallback callback) {
        this.activity = activity;
        this.callback = callback;

        try {
            this.mapView = new TextureMapView(activity);
            this.aMap = this.mapView.getMap();
            this.uiSettings = this.aMap.getUiSettings();

            // AMap requires onCreate before the view is attached.
            this.mapView.onCreate(null);

            // A modern, phone-map look: pinch to zoom, no +/- buttons, no compass clutter.
            this.uiSettings.setZoomControlsEnabled(false);

            final ViewGroup container = activity.findViewById(containerViewId);
            if (container != null) {
                container.addView(this.mapView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                Log.e(TAG, "Map container " + containerViewId + " was not found");
            }

            if (this.callback != null) {
                this.callback.onMapReady(this);
            }
        } catch (Exception e) {
            // No key, no network, no GL surface: the screen must still open. A blank map with
            // a log line beats an activity that dies on entry.
            Log.e(TAG, "Failed to initialise the AMap view", e);
        }
    }

    @Override
    public void setMapStyle(MapStyle mapStyle) {
        this.currentMapStyle = mapStyle == null ? MapStyle.FOLLOW_SYSTEM : mapStyle;
        if (this.aMap == null || this.destroyed) return;

        try {
            this.aMap.setMapType(this.shouldUseDarkMap() ? AMap.MAP_TYPE_NIGHT : AMap.MAP_TYPE_NORMAL);
        } catch (Exception e) {
            Log.w(TAG, "Failed to switch the map style", e);
        }
    }

    private boolean shouldUseDarkMap() {
        if (this.currentMapStyle == MapStyle.DARK) return true;
        if (this.currentMapStyle == MapStyle.LIGHT) return false;
        if (this.activity == null) return false;
        final int night = this.activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public String addMarker(MapMarker marker) {
        if (this.aMap == null || this.destroyed) {
            Log.w(TAG, "Map is not ready; marker " + marker.getId() + " was not added");
            return marker.getId();
        }

        try {
            final double[] gcj02 = CoordinateConverter.wgs84ToGcj02(
                    marker.getLatitude(), marker.getLongitude());

            final MarkerOptions options = new MarkerOptions()
                    .position(new LatLng(gcj02[0], gcj02[1]))
                    .zIndex(marker.getZIndex());

            if (marker.getTitle() != null) options.title(marker.getTitle());
            if (marker.getSnippet() != null) options.snippet(marker.getSnippet());

            if (marker.getIconBitmap() != null) {
                options.icon(BitmapDescriptorFactory.fromBitmap(marker.getIconBitmap()));
            } else if (marker.getIconResourceId() != 0) {
                options.icon(BitmapDescriptorFactory.fromResource(marker.getIconResourceId()));
            }

            final Marker amapMarker = this.aMap.addMarker(options);
            if (amapMarker == null) return marker.getId();

            this.markers.put(marker.getId(), amapMarker);
            this.markerIds.put(amapMarker, marker.getId());
            return marker.getId();
        } catch (Exception e) {
            Log.e(TAG, "Failed to add marker " + marker.getId(), e);
            return marker.getId();
        }
    }

    @Override
    public void removeMarker(String markerId) {
        final Marker marker = this.markers.remove(markerId);
        if (marker == null) return;
        this.markerIds.remove(marker);
        try {
            marker.remove();
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove marker " + markerId, e);
        }
    }

    @Override
    public void setMarkerZIndex(String markerId, float zIndex) {
        final Marker marker = this.markers.get(markerId);
        if (marker == null) return;
        try {
            marker.setZIndex(zIndex);
        } catch (Exception e) {
            // Not fatal: the marker stays where it is, just possibly behind another one.
            Log.w(TAG, "Failed to re-order marker " + markerId, e);
        }
    }

    @Override
    public void clearMarkers() {
        for (Marker marker : this.markers.values()) {
            try {
                marker.remove();
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove a marker while clearing", e);
            }
        }
        this.markers.clear();
        this.markerIds.clear();
    }

    @Override
    public String addPolyline(MapPolyline polyline) {
        if (this.aMap == null || this.destroyed) return polyline.getId();

        try {
            final List<MapPolyline.LatLng> points = polyline.getPoints();
            if (points == null || points.size() < 2) return polyline.getId();

            final LatLng[] converted = new LatLng[points.size()];
            for (int i = 0; i < points.size(); i++) {
                final MapPolyline.LatLng point = points.get(i);
                final double[] gcj02 = CoordinateConverter.wgs84ToGcj02(
                        point.getLatitude(), point.getLongitude());
                converted[i] = new LatLng(gcj02[0], gcj02[1]);
            }

            final PolylineOptions options = new PolylineOptions()
                    .add(converted)
                    .color(polyline.getColor())
                    .width(polyline.getWidth());

            final Polyline amapPolyline = this.aMap.addPolyline(options);
            if (amapPolyline == null) return polyline.getId();

            this.polylines.put(polyline.getId(), amapPolyline);
            return polyline.getId();
        } catch (Exception e) {
            Log.e(TAG, "Failed to add polyline " + polyline.getId(), e);
            return polyline.getId();
        }
    }

    @Override
    public void removePolyline(String polylineId) {
        final Polyline polyline = this.polylines.remove(polylineId);
        if (polyline == null) return;
        try {
            polyline.remove();
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove polyline " + polylineId, e);
        }
    }

    @Override
    public void clearPolylines() {
        for (Polyline polyline : this.polylines.values()) {
            try {
                polyline.remove();
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove a polyline while clearing", e);
            }
        }
        this.polylines.clear();
    }

    @Override
    public void moveCamera(double latitude, double longitude, float zoom) {
        if (!this.isUsable()) return;
        try {
            this.aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(this.toGcj02(latitude, longitude), zoom));
        } catch (Exception e) {
            Log.e(TAG, "Failed to move the camera", e);
        }
    }

    @Override
    public void animateCamera(double latitude, double longitude, float zoom, Runnable callback) {
        if (!this.isUsable()) return;
        try {
            final CameraUpdate update =
                    CameraUpdateFactory.newLatLngZoom(this.toGcj02(latitude, longitude), zoom);

            if (callback == null) {
                this.aMap.animateCamera(update);
                return;
            }
            this.aMap.animateCamera(update, 1000L, new AMap.CancelableCallback() {
                @Override
                public void onFinish() {
                    callback.run();
                }

                @Override
                public void onCancel() {
                    // The movement was interrupted by another one, which will report its own
                    // completion. Running the callback here would double-fire the caller's
                    // "I've arrived" work - HistoryViewActivity uses it to drop a padding
                    // override, and doing that twice leaves the map padded for the sheet.
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to animate the camera", e);
            if (callback != null) callback.run();
        }
    }

    /**
     * Frame a set of points.
     * <br>
     * AMap computes the zoom that fits for us, which the shared fallback cannot: it centres on
     * the midpoint at a fixed zoom, so a history stretching across a province lands outside the
     * screen.
     */
    @Override
    public void fitBounds(List<MapPolyline.LatLng> points, int paddingPx, Runnable callback) {
        if (!this.isUsable() || points == null || points.isEmpty()) {
            if (callback != null) callback.run();
            return;
        }

        try {
            final LatLngBounds.Builder builder = LatLngBounds.builder();
            for (MapPolyline.LatLng point : points) {
                builder.include(this.toGcj02(point.getLatitude(), point.getLongitude()));
            }
            final CameraUpdate update =
                    CameraUpdateFactory.newLatLngBounds(builder.build(), paddingPx);

            if (callback == null) {
                this.aMap.animateCamera(update);
                return;
            }
            this.aMap.animateCamera(update, 1000L, new AMap.CancelableCallback() {
                @Override
                public void onFinish() {
                    callback.run();
                }

                @Override
                public void onCancel() {
                    // See animateCamera: an interrupted move is followed by another one.
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to frame the given bounds", e);
            if (callback != null) callback.run();
        }
    }

    @Override
    public void setOnMapClickListener(OnMapClickListener listener) {
        this.onMapClickListener = listener;
        if (this.aMap == null || this.destroyed) return;

        try {
            this.aMap.setOnMapClickListener(latLng -> {
                if (latLng == null) return;
                final OnMapClickListener target = this.onMapClickListener;
                if (target == null) return;
                // GCJ-02 back to WGS-84: everything the app stores or reports is WGS-84.
                final double[] wgs84 = CoordinateConverter.gcj02ToWgs84(
                        latLng.latitude, latLng.longitude);
                target.onMapClick(wgs84[0], wgs84[1]);
            });
        } catch (Exception e) {
            Log.w(TAG, "Failed to set the map click listener", e);
        }
    }

    @Override
    public void setOnMarkerClickListener(OnMarkerClickListener listener) {
        this.onMarkerClickListener = listener;
        if (this.aMap == null || this.destroyed) return;

        try {
            this.aMap.setOnMarkerClickListener(marker -> {
                final OnMarkerClickListener target = this.onMarkerClickListener;
                final String id = marker == null ? null : this.markerIds.get(marker);
                // Consume the tap only when somebody handled it, so AMap's default info
                // window still appears for markers this class did not create.
                return id != null && target != null && target.onMarkerClick(id);
            });
        } catch (Exception e) {
            Log.w(TAG, "Failed to set the marker click listener", e);
        }
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        // AMap has no camera padding: the camera centre is the view centre. The part that
        // matters visually is its logo, which would sit under the tag cards, so lift that.
        if (this.uiSettings == null || this.activity == null) return;
        try {
            final float density = this.activity.getResources().getDisplayMetrics().density;
            this.uiSettings.setLogoBottomMargin(Math.max(bottom, (int) (LOGO_BOTTOM_MARGIN_DP * density)));
        } catch (Exception e) {
            Log.w(TAG, "Failed to move the map logo", e);
        }
    }

    @Override
    public CameraPosition getCameraPosition() {
        if (!this.isUsable()) return null;

        try {
            // 全限定：本类实现了 IMapProvider，同名的 CameraPosition 会解析到接口里那个。
            final com.amap.api.maps.model.CameraPosition position =
                    this.aMap.getCameraPosition();
            if (position == null || position.target == null) return null;

            final double[] wgs84 = CoordinateConverter.gcj02ToWgs84(
                    position.target.latitude, position.target.longitude);
            return new CameraPosition(wgs84[0], wgs84[1], position.zoom);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read the camera position", e);
            return null;
        }
    }

    @Override
    public void setMyLocationButtonEnabled(boolean enabled) {
        if (this.uiSettings == null || this.destroyed) return;
        try {
            this.uiSettings.setMyLocationButtonEnabled(enabled);
        } catch (Exception e) {
            Log.w(TAG, "Failed to set the my-location button", e);
        }
    }

    @Override
    public void setMyLocationEnabled(boolean enabled) {
        if (!this.isUsable()) return;
        try {
            this.aMap.setMyLocationEnabled(enabled);
        } catch (Exception e) {
            // Includes the SecurityException a revoked permission raises. The map is usable
            // without the dot, so this is reported and not thrown.
            Log.w(TAG, "Failed to toggle the my-location dot", e);
        }
    }

    @Override
    public void setRotateGesturesEnabled(boolean enabled) {
        if (this.uiSettings == null || this.destroyed) return;
        try {
            this.uiSettings.setRotateGesturesEnabled(enabled);
        } catch (Exception e) {
            Log.w(TAG, "Failed to toggle rotate gestures", e);
        }
    }

    @Override
    public void setCompassEnabled(boolean enabled) {
        if (this.uiSettings == null || this.destroyed) return;
        try {
            this.uiSettings.setCompassEnabled(enabled);
        } catch (Exception e) {
            Log.w(TAG, "Failed to toggle the compass", e);
        }
    }

    @Override
    public void setMapToolbarEnabled(boolean enabled) {
        // AMap has no equivalent toolbar; the app draws its own buttons.
    }

    @Override
    public void clear() {
        clearMarkers();
        clearPolylines();
    }

    @Override
    public View getMapView() {
        return this.mapView;
    }

    @Override
    public void onResume() {
        if (this.mapView == null || this.destroyed) return;
        try {
            this.mapView.onResume();
        } catch (Exception e) {
            Log.w(TAG, "Failed to resume the map view", e);
        }
    }

    @Override
    public void onPause() {
        if (this.mapView == null || this.destroyed) return;
        try {
            this.mapView.onPause();
        } catch (Exception e) {
            Log.w(TAG, "Failed to pause the map view", e);
        }
    }

    /**
     * Tear the SDK view down exactly once, and detach it first.
     * <br>
     * <b>Order matters.</b> {@code onDestroy} releases the GL surface and the native engine; a
     * view still attached to a window at that point is destroyed by the framework later, in a
     * different order, and the native side then frees objects the engine already freed. The
     * dump this app shipped for months was that abort. Removing the view from its parent first
     * means the framework never touches it again.
     */
    @Override
    public void onDestroy() {
        if (this.destroyed) return;
        this.destroyed = true;

        this.clearMarkers();
        this.clearPolylines();

        final TextureMapView view = this.mapView;
        this.mapView = null;
        this.aMap = null;
        this.uiSettings = null;

        if (view == null) return;

        try {
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to detach the map view", e);
        }

        try {
            view.onDestroy();
        } catch (Exception e) {
            Log.w(TAG, "Failed to destroy the map view", e);
        }
    }

    private boolean isUsable() {
        return this.aMap != null && !this.destroyed;
    }

    private LatLng toGcj02(double latitude, double longitude) {
        final double[] gcj02 = CoordinateConverter.wgs84ToGcj02(latitude, longitude);
        return new LatLng(gcj02[0], gcj02[1]);
    }
}
