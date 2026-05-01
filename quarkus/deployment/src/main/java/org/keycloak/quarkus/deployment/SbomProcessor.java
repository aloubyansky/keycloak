package org.keycloak.quarkus.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.sbom.SbomContributionBuildItem;
import io.quarkus.sbom.ComponentDependencies;
import io.quarkus.sbom.ComponentDescriptor;
import io.quarkus.sbom.Purl;
import io.quarkus.sbom.SbomContribution;

public class SbomProcessor {

    private static final Logger log = Logger.getLogger(SbomProcessor.class);

    private static final String NPM_MANIFEST_RESOURCE = "META-INF/npm-sbom-manifest.json";

    @BuildStep
    void contributeNpmComponents(BuildProducer<SbomContributionBuildItem> sbomProducer) {
        var manifestNode = loadManifest();
        if (manifestNode == null) {
            return;
        }

        var packagesNode = manifestNode.get("packages");
        if (packagesNode == null || !packagesNode.isArray() || packagesNode.isEmpty()) {
            log.warn("npm SBOM manifest contains no packages");
            return;
        }

        var builder = SbomContribution.builder();
        int count = 0;
        for (var pkgNode : packagesNode) {
            var name = pkgNode.get("name").asText();
            var version = pkgNode.get("version").asText();
            var directNode = pkgNode.get("direct");
            var direct = directNode != null && directNode.asBoolean();

            builder.addComponent(ComponentDescriptor.builder()
                    .setPurl(toNpmPurl(name, version))
                    .setScope(ComponentDescriptor.SCOPE_RUNTIME)
                    .setTopLevel(direct)
                    .build());
            count++;
        }

        var depsNode = manifestNode.get("dependencies");
        if (depsNode != null && depsNode.isObject()) {
            var fields = depsNode.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                var bomRef = toPurlString(entry.getKey());
                List<String> dependsOn = new ArrayList<>();
                for (var depRef : entry.getValue()) {
                    dependsOn.add(toPurlString(depRef.asText()));
                }
                builder.addDependency(ComponentDependencies.of(bomRef, dependsOn));
            }
        }

        log.infof("Contributing %d npm packages to SBOM", count);
        sbomProducer.produce(new SbomContributionBuildItem(builder.build()));
    }

    private static Purl toNpmPurl(String name, String version) {
        String scope = null;
        String pkgName = name;
        if (name.startsWith("@")) {
            int slashIdx = name.indexOf('/');
            if (slashIdx > 0) {
                scope = name.substring(0, slashIdx);
                pkgName = name.substring(slashIdx + 1);
            }
        }
        return Purl.npm(scope, pkgName, version);
    }

    private static String toPurlString(String nameAtVersion) {
        int atIdx = nameAtVersion.lastIndexOf('@');
        if (atIdx <= 0) {
            return nameAtVersion;
        }
        var name = nameAtVersion.substring(0, atIdx);
        var version = nameAtVersion.substring(atIdx + 1);
        return toNpmPurl(name, version).toString();
    }

    private JsonNode loadManifest() {
        var cl = Thread.currentThread().getContextClassLoader();
        try (InputStream is = cl.getResourceAsStream(NPM_MANIFEST_RESOURCE)) {
            if (is == null) {
                log.debug("No npm SBOM manifest found on classpath");
                return null;
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                return new ObjectMapper().readTree(reader);
            }
        } catch (IOException e) {
            log.warn("Failed to read npm SBOM manifest", e);
            return null;
        }
    }
}
