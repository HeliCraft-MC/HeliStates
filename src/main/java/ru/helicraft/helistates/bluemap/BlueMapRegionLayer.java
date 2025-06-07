package ru.helicraft.helistates.bluemap;

import com.flowpowered.math.vector.Vector2d; // ← Flow-Math!
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import ru.helicraft.helistates.region.RegionManager;
import ru.helicraft.states.regions.RegionGenerator;

import java.util.List;

/**
 * Отрисовывает «свободные» регионы на BlueMap
 * серой полупрозрачной заливкой.
 */
public final class BlueMapRegionLayer {

    private static final String SET_ID = "helistates_regions";
    private static final Color  FILL   = new Color(128, 128, 128, .5f);
    private static final Color  LINE   = new Color( 96,  96,  96, 1f);

    private final MarkerSet set = MarkerSet.builder()
            .label("Свободные регионы")
            .toggleable(true)
            .defaultHidden(false)
            .build();

    public BlueMapRegionLayer(RegionManager regionManager) {

        /* реагируем на изменения в RegionManager */
        regionManager.addUpdateListener(this::renderRegions);

        /* регистрируемся в BlueMap */
        BlueMapAPI.onEnable(api -> {
            api.getWorlds().forEach(w -> w.getMaps().forEach(this::attach));
            renderRegions(regionManager.getRegions());
        });
        BlueMapAPI.onDisable(api -> set.getMarkers().clear());
    }

    private void attach(BlueMapMap map) {
        map.getMarkerSets().put(SET_ID, set);
    }

    /* перерисовать все регионы */
    private void renderRegions(List<RegionGenerator.Region> regs) {
        set.getMarkers().clear();
        regs.forEach(r -> set.getMarkers().put(
                r.id().toString(), buildMarker(r)
        ));
    }

    private ShapeMarker buildMarker(RegionGenerator.Region r) {
        List<Vector2d> poly = r.outline().stream()
                .map(v -> new Vector2d(v.getBlockX(), v.getBlockZ()))
                .toList();

        return ShapeMarker.builder()
                .label("Незанятый регион")
                .shape(new Shape(poly), 64f) // y-координата
                .fillColor(FILL)
                .lineColor(LINE)
                .lineWidth(1)
                .build();
    }
}
