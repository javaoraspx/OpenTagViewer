package dev.wander.android.opentagviewer.ui.maps;

import android.app.Activity;
import android.view.View;

import java.util.List;

/**
 * 地图提供商抽象接口
 * 定义统一的地图操作API,支持多地图SDK切换
 * 
 * 实现类:
 * - AMapProvider: 高德地图实现
 */
public interface IMapProvider {
    
    /**
     * 初始化地图
     * @param activity 当前Activity
     * @param containerViewId 地图容器View的ID
     * @param callback 地图就绪回调
     */
    void initialize(Activity activity, int containerViewId, OnMapReadyCallback callback);
    
    /**
     * 设置地图样式
     */
    void setMapStyle(MapStyle mapStyle);
    
    /**
     * 添加标记点
     * @param marker 标记点数据
     * @return 标记点ID
     */
    String addMarker(MapMarker marker);
    
    /**
     * 移除标记点
     * @param markerId 标记点ID
     */
    void removeMarker(String markerId);

    /**
     * 设置标记点的绘制顺序（数值大的显示在上层）
     * <br>
     * Raises or lowers an existing marker. Tags kept in the same place overlap completely at
     * anything but the closest zoom, so selecting one has to bring it to the front - otherwise
     * the user taps a card and nothing visibly happens.
     *
     * @param markerId 标记点ID
     * @param zIndex 绘制顺序
     */
    void setMarkerZIndex(String markerId, float zIndex);

    /**
     * 清除所有标记点
     */
    void clearMarkers();
    
    /**
     * 添加路径线
     * @param polyline 路径线数据
     * @return 路径线ID
     */
    String addPolyline(MapPolyline polyline);
    
    /**
     * 移除路径线
     * @param polylineId 路径线ID
     */
    void removePolyline(String polylineId);
    
    /**
     * 清除所有路径线
     */
    void clearPolylines();
    
    /**
     * 移动相机（不带动画）
     * @param latitude 纬度
     * @param longitude 经度
     * @param zoom 缩放级别
     */
    void moveCamera(double latitude, double longitude, float zoom);
    
    /**
     * 动画移动相机
     * @param latitude 纬度
     * @param longitude 经度
     * @param zoom 缩放级别
     * @param callback 动画完成回调(可为null)
     */
    void animateCamera(double latitude, double longitude, float zoom, Runnable callback);

    /**
     * Frame a set of points, zooming out until they all fit.
     * <br>
     * A default rather than abstract, because a provider whose SDK has no "fit bounds" can still
     * answer sensibly - centre on the middle at a fixed zoom - and that is what this class did
     * before the call existed. A provider that can do better (AMap computes the zoom itself)
     * overrides it.
     *
     * @param points    WGS-84 points to frame; empty means "nothing to do"
     * @param paddingPx space to leave around the result, in pixels
     * @param callback  run when the camera settles, or null
     */
    default void fitBounds(List<MapPolyline.LatLng> points, int paddingPx, Runnable callback) {
        if (points == null || points.isEmpty()) {
            if (callback != null) callback.run();
            return;
        }

        double latMin = Double.MAX_VALUE;
        double latMax = -Double.MAX_VALUE;
        double lonMin = Double.MAX_VALUE;
        double lonMax = -Double.MAX_VALUE;
        for (MapPolyline.LatLng point : points) {
            latMin = Math.min(latMin, point.getLatitude());
            latMax = Math.max(latMax, point.getLatitude());
            lonMin = Math.min(lonMin, point.getLongitude());
            lonMax = Math.max(lonMax, point.getLongitude());
        }

        final double span = Math.max(latMax - latMin, lonMax - lonMin);
        // Roughly: z16 is a few hundred metres across, and each level doubles that. Only a
        // fallback, so a simple rule beats a precise one nobody can check.
        float zoom = 16.0f;
        if (span > 0.0005) zoom = 15.0f;
        if (span > 0.002) zoom = 13.0f;
        if (span > 0.01) zoom = 11.0f;
        if (span > 0.05) zoom = 9.0f;
        if (span > 0.2) zoom = 7.0f;

        animateCamera((latMin + latMax) / 2.0, (lonMin + lonMax) / 2.0, zoom, callback);
    }
    
    /**
     * 设置地图点击监听器
     * @param listener 点击监听器
     */
    void setOnMapClickListener(OnMapClickListener listener);
    
    /**
     * 设置标记点击监听器
     * @param listener 点击监听器
     */
    void setOnMarkerClickListener(OnMarkerClickListener listener);
    
    /**
     * 设置地图内边距
     * @param left 左边距
     * @param top 上边距
     * @param right 右边距
     * @param bottom 下边距
     */
    void setPadding(int left, int top, int right, int bottom);
    
    /**
     * 获取当前相机位置
     * @return 相机位置信息
     */
    CameraPosition getCameraPosition();
    
    /**
     * 设置我的位置按钮是否可见
     * @param enabled true为可见
     */
    void setMyLocationButtonEnabled(boolean enabled);

    /**
     * Show the device's own position on the map, as a dot or the provider's equivalent.
     *
     * <p><b>Deliberately not a default method.</b> This is the one the activity was calling on
     * the {@code GoogleMap} object directly, around this interface - so it worked on Google Maps
     * and silently did nothing on every other provider, which is exactly the branch rule 7 exists
     * to prevent. Leaving it abstract means a new provider has to answer the question rather than
     * inherit a no-op and look finished.
     *
     * <p>The caller is responsible for holding a location permission first; a provider that is
     * handed {@code true} without one should fail quietly rather than throw into the map.
     *
     * @param enabled true to show the device's position
     */
    void setMyLocationEnabled(boolean enabled);
    
    /**
     * 设置旋转手势是否可用
     * @param enabled true为可用
     */
    void setRotateGesturesEnabled(boolean enabled);
    
    /**
     * 设置指南针是否可见
     * @param enabled true为可见
     */
    void setCompassEnabled(boolean enabled);
    
    /**
     * 设置地图工具栏是否可见
     * @param enabled true为可见
     */
    void setMapToolbarEnabled(boolean enabled);
    
    /**
     * 清除地图上所有覆盖物（标记点、路径线等）
     */
    void clear();
    
    /**
     * 获取地图View对象
     * @return 地图View
     */
    View getMapView();

    /**
     * The zoom this provider opens a tag at.
     *
     * <p><b>Per provider, because a zoom level is not a distance.</b> The number is an index into
     * whatever tile pyramid the provider draws, and two providers at the same index do not
     * necessarily show the same amount of street - so one value shared across all of them means
     * one of them is wrong, and which one depends on who last tuned it.
     *
     * <p>Google's scale is the reference (level 16 is "streets"), so that is the default and
     * nothing that was working changes by this existing.
     */
    default float initialZoom() {
        return 16.0f;
    }

    /**
     * Forwarded from the hosting activity, for providers whose map view needs the callback.
     *
     * <p><b>Default no-ops so that a provider which does not care says nothing</b>, and - the
     * actual point - so the activity never asks which provider it has. It used to: three
     * {@code instanceof AMapProvider} blocks, one per callback, and the next provider added
     * copied them. That is the branching {@code IMapProvider} exists to prevent (AGENTS.md rule
     * 7), and a new implementation should need no edit in {@code MapsActivity} at all.
     *
     * <p>AMap's {@code TextureMapView} requires all three and crashes natively if destroyed
     * twice or after its activity is gone - see {@code AMapProvider.onDestroy()}.
     */
    default void onResume() {
    }

    /** See {@link #onResume()}. */
    default void onPause() {
    }

    /** See {@link #onResume()}. */
    default void onDestroy() {
    }
    
    /**
     * 地图就绪回调接口
     */
    interface OnMapReadyCallback {
        /**
         * 地图已就绪,可以开始操作
         * @param mapProvider 地图提供商实例
         */
        void onMapReady(IMapProvider mapProvider);
    }
    
    /**
     * 地图点击监听器
     */
    interface OnMapClickListener {
        /**
         * 地图被点击
         * @param latitude 点击位置的纬度
         * @param longitude 点击位置的经度
         */
        void onMapClick(double latitude, double longitude);
    }
    
    /**
     * 标记点击监听器
     */
    interface OnMarkerClickListener {
        /**
         * 标记点被点击
         * @param markerId 标记点ID
         * @return true表示消费事件,false表示不消费
         */
        boolean onMarkerClick(String markerId);
    }
    
    /**
     * 相机位置信息
     */
    class CameraPosition {
        private final double latitude;
        private final double longitude;
        private final float zoom;
        
        public CameraPosition(double latitude, double longitude, float zoom) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.zoom = zoom;
        }
        
        public double getLatitude() {
            return latitude;
        }
        
        public double getLongitude() {
            return longitude;
        }
        
        public float getZoom() {
            return zoom;
        }
    }

    enum MapStyle {
        LIGHT,
        DARK,
        FOLLOW_SYSTEM
    }
}
