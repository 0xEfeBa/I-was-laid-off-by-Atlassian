package dev.triedge.broker.service;

import dev.triedge.broker.data.Tenant;
import java.util.UUID;

public record Actor(UUID id, boolean admin) {

    public boolean canSee(Tenant tenant) {
        return admin || tenant.ownerId().equals(id);
    }
}
