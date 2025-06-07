package ru.helicraft.helistates.region;

import org.bukkit.util.BoundingBox;

import java.util.UUID;

public class Region {
    private final UUID id;
    private final BoundingBox box;
    private final boolean ocean;

    public Region(UUID id, BoundingBox box, boolean ocean) {
        this.id = id;
        this.box = box;
        this.ocean = ocean;
    }

    public UUID getId() {
        return id;
    }

    public BoundingBox getBox() {
        return box;
    }

    public boolean isOcean() {
        return ocean;
    }
}
