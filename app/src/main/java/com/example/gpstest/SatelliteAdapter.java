package com.example.gpstest;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * Adapter fait main (BaseAdapter) — version pédagogique.
 * Affiche : constellation + PRN, bande (L1/L5), barre de C/N0, valeur en dBHz.
 * La liste affichée est déjà filtrée et triée par SatelliteFilter.
 */
public class SatelliteAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private List<SatInfo> data;

    public SatelliteAdapter(Context context, List<SatInfo> data) {
        this.inflater = LayoutInflater.from(context);
        this.data = data;
    }

    /** Remplace la liste affichée (après filtrage). */
    public void setData(List<SatInfo> data) {
        this.data = data;
    }

    @Override public int getCount() { return data == null ? 0 : data.size(); }
    @Override public Object getItem(int position) { return data.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_satellite, parent, false);
            holder = new ViewHolder();
            holder.name = convertView.findViewById(R.id.tvSatName);
            holder.band = convertView.findViewById(R.id.tvSatBand);
            holder.bar  = convertView.findViewById(R.id.pbSatSnr);
            holder.cn0  = convertView.findViewById(R.id.tvSatSnr);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        SatInfo sat = data.get(position);

        // Le préfixe indique visuellement s'il participe au fix
        holder.name.setText(String.format(Locale.FRANCE, "%s %s %d",
                sat.inFix ? "●" : "○", constellationSymbol(sat.constellation), sat.svid));

        holder.cn0.setText(Float.isFinite(sat.cn0) && sat.cn0 > 0f
                ? String.format(Locale.FRANCE, "%.0f dB-Hz", sat.cn0) : "— dB-Hz");

        String band = sat.band();
        holder.band.setText(band);
        // Couleurs lues depuis les ressources : elles doivent rester
        // lisibles en mode clair. Le vert #64DD17 sur fond blanc a un
        // contraste de 1,9:1 — illisible ; @color/gps_green vaut #2E7D32 en
        // clair (4,8:1) et #64DD17 en sombre (là, sur fond noir, il est
        // parfaitement lisible). Un seul nom, deux valeurs : voir colors.xml.
        android.content.Context c = holder.band.getContext();
        holder.band.setTextColor("L5".equals(band)
                ? ThemeManager.color(c, R.color.gps_green, 0xFF2E7D32)
                : ThemeManager.color(c, R.color.gps_amber, 0xFFB26A00));

        int pct = Math.round(Utils.snrFraction(sat.cn0) * 100f);
        holder.bar.setProgress(pct);

        int color;
        if (pct >= 60)      color = ThemeManager.color(c, R.color.gps_signal, 0xFF00A83B);
        else if (pct >= 35) color = ThemeManager.color(c, R.color.gps_warn,   0xFFE08A00);
        else                color = ThemeManager.color(c, R.color.gps_alert,  0xFFD32F2F);

        holder.bar.setProgressTintList(ColorStateList.valueOf(color));

        // Les satellites non utilisés dans le fix sont atténués
        convertView.setAlpha(sat.inFix ? 1.0f : 0.5f);

        return convertView;
    }

    private static String constellationSymbol(String name) {
        if (name == null) return "?";
        switch (name.toUpperCase(Locale.ROOT)) {
            case "GPS": return "G";
            case "GLONASS": return "R";
            case "GALILEO": return "E";
            case "BEIDOU": return "C";
            case "QZSS": return "J";
            case "IRNSS": return "I";
            case "SBAS": return "S";
            default: return "?";
        }
    }

    private static class ViewHolder {
        TextView name, band, cn0;
        ProgressBar bar;
    }
}
