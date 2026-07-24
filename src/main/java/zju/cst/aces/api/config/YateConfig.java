package zju.cst.aces.api.config;

import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.nio.file.Paths;

@Getter
@Setter
public class YateConfig {
    public boolean useOracleFixing = false;
    public String modelName = null;
    public String outputDirectory = null;
    /**
     * Path to the Yate {@code .env.chatunitest} configuration file.
     *
     * <p>Historically this was a hard-coded absolute path pointing at the original author's
     * machine. It is now resolved (in order of precedence) from:</p>
     * <ol>
     *     <li>the {@code CHATUNITEST_YATE_ENV} environment variable,</li>
     *     <li>the {@code chatunitest.yate.env} JVM system property,</li>
     *     <li>a {@code .env.chatunitest} file in the current working directory (the template
     *         shipped at the repository root).</li>
     * </ol>
     * The driver ({@link zju.cst.aces.driver.Defects4jDriver}) may also set this explicitly
     * via {@code config.getYate().setConfigPath(...)}.
     */
    public String configPath = resolveDefaultConfigPath();

    private static String resolveDefaultConfigPath() {
        String fromEnv = System.getenv("CHATUNITEST_YATE_ENV");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String fromProp = System.getProperty("chatunitest.yate.env");
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp;
        }
        return Paths.get(System.getProperty("user.dir"), ".env.chatunitest").toString();
    }
}
