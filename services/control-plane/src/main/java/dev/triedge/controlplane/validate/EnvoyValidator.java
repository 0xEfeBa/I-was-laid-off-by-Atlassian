package dev.triedge.controlplane.validate;

import dev.triedge.controlplane.render.EnvoyResources;
import java.util.List;

public interface EnvoyValidator {

    List<String> validate(EnvoyResources resources);
}
