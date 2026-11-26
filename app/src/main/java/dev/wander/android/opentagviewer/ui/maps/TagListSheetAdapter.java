package dev.wander.android.opentagviewer.ui.maps;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.ble.FindMyAdvertisement;
import dev.wander.android.opentagviewer.ble.NearbyTagLabel;

/**
 * "全部标签"抽屉的列表适配器。
 *
 * <p><b>为什么是固定行高的列表，而不是让每行自己撑开。</b> 抽屉是盖在地图上的，它的高度
 * 由内容决定；一行地址换行就会让整张地图再往上缩一截，滑动时还会跳。统一成单行 + 省略号，
 * 抽屉高度就只和标签数量有关，滚动是平滑的。完整的地址仍然在地图卡片上，那里空间足够。
 *
 * <p>行不做 diff（没有 DiffUtil）：一次重绘最多几十行，都来自同一个 {@code beacons} 列表的
 * 全量替换，算 diff 的开销比直接重建还大。
 */
public class TagListSheetAdapter extends RecyclerView.Adapter<TagListSheetAdapter.Row> {

    /** 一行要显示的东西。由 {@code MapsActivity} 在每次重绘卡片时一并填好。 */
    public static final class Entry {
        final String beaconId;
        final String name;
        final String emoji;
        final String address;
        final String age;
        /**
         * 蓝牙刚刚听见它时的电量，null 表示没有可显示的电量。
         *
         * <p><b>传等级而不是资源 id。</b> 资源 id 是构建产物里的整数，换一个语言包或换一次
         * 构建就变；让适配器自己按等级取图标，跨进程状态和测试里都不会指向错的东西。
         */
        @Nullable
        final FindMyAdvertisement.BatteryLevel batteryLevel;

        public Entry(final String beaconId, final String name, final String emoji,
                     final String address, final String age,
                     @Nullable final FindMyAdvertisement.BatteryLevel batteryLevel) {
            this.beaconId = beaconId;
            this.name = name;
            this.emoji = emoji;
            this.address = address;
            this.age = age;
            this.batteryLevel = batteryLevel;
        }
    }

    public interface OnTagClicked {
        /** 用户点了某一行：把地图移过去，并收起抽屉。 */
        void onTagClicked(@NonNull String beaconId);
    }

    private final List<Entry> entries = new ArrayList<>();
    private final OnTagClicked onTagClicked;

    public TagListSheetAdapter(final OnTagClicked onTagClicked) {
        this.onTagClicked = onTagClicked;
        setHasStableIds(false);
    }

    public void submit(final List<Entry> next) {
        this.entries.clear();
        this.entries.addAll(next);
        notifyDataSetChanged();
    }

    public int size() {
        return this.entries.size();
    }

    @NonNull
    @Override
    public Row onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.tag_list_sheet_item, parent, false);
        return new Row(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final Row holder, final int position) {
        holder.bind(this.entries.get(position), this.onTagClicked);
    }

    @Override
    public int getItemCount() {
        return this.entries.size();
    }

    static final class Row extends RecyclerView.ViewHolder {
        private final TextView emoji;
        private final ImageView icon;
        private final TextView name;
        private final TextView address;
        private final TextView age;
        private final ImageView battery;

        Row(@NonNull final View view) {
            super(view);
            this.emoji = view.findViewById(R.id.tag_row_emoji);
            this.icon = view.findViewById(R.id.tag_row_icon);
            this.name = view.findViewById(R.id.tag_row_name);
            this.address = view.findViewById(R.id.tag_row_address);
            this.age = view.findViewById(R.id.tag_row_age);
            this.battery = view.findViewById(R.id.tag_row_battery);
        }

        void bind(final Entry entry, final OnTagClicked onTagClicked) {
            this.name.setText(entry.name);

            // emoji 优先：用户或他的 Apple 设备设置的图标永远胜过我们的默认图标。
            // 与地图卡片上的规则一致，否则同一枚标签在两个地方长得不一样。
            final boolean hasEmoji = entry.emoji != null && !entry.emoji.isBlank();
            this.emoji.setText(hasEmoji ? entry.emoji : "");
            this.emoji.setVisibility(hasEmoji ? VISIBLE : GONE);
            this.icon.setVisibility(hasEmoji ? GONE : VISIBLE);

            // 没有地址时整行隐藏，而不是留一个空行——空行会让人以为地址加载失败了。
            final boolean hasAddress = entry.address != null && !entry.address.isBlank();
            this.address.setText(hasAddress ? entry.address : "");
            this.address.setVisibility(hasAddress ? VISIBLE : GONE);

            this.age.setText(entry.age == null ? "" : entry.age);

            if (entry.batteryLevel != null) {
                // 与 NearbyBatteryIcon 同一套图标，但那个方法还要写 contentDescription；
                // 列表行整行可点，读屏念的是标签名，多一个电量描述反而打断朗读。
                this.battery.setImageResource(NearbyTagLabel.batteryIcon(entry.batteryLevel));
                this.battery.setContentDescription(null);
                this.battery.setVisibility(VISIBLE);
            } else {
                this.battery.setVisibility(GONE);
            }

            this.itemView.setOnClickListener(v -> onTagClicked.onTagClicked(entry.beaconId));
        }
    }
}
