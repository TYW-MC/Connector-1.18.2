package org.sinytra.connector.mod.mixin.boot;

import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes the "swallowed boot failure" visible.
 *
 * <p><b>The problem.</b> 1.18.2's {@code net.minecraft.client.main.Main#main} wraps everything in
 * <pre>
 *   } catch (Throwable throwable) {
 *       CrashReport crashreport = CrashReport.forThrowable(throwable, "Initializing game");
 *       ...
 *       Minecraft.fillReport((Minecraft) null, (LanguageManager) null, gameConfig, (Options) null, crashreport);
 *       Minecraft.getInstance().crash(crashreport);      // &lt;-- Main.java:179
 *   }
 * </pre>
 * {@code Main} uses the single-arg form {@code Minecraft.getInstance().crash(...)}. When the failure
 * happened <i>inside</i> the {@code Minecraft} constructor, the static {@code INSTANCE} field was
 * never assigned, so {@code getInstance()} returns {@code null} and that very line throws
 * <pre>
 *   java.lang.NullPointerException: Cannot read field "f_91069_" because the return value of
 *   "net.minecraft.client.Minecraft.m_91087_()" is null
 *       at net.minecraft.client.Minecraft.m_91332_(Minecraft.java:775)
 *       at net.minecraft.client.main.Main.main(Main.java:179)
 * </pre>
 * The NPE is thrown <i>from inside the catch block</i>, so Java discards the original throwable and
 * the only thing anyone ever sees is that useless NPE - no crash report file, no stack trace.
 * (Verified against the shipped bytecode: {@code Main.main} offset 1429 does
 * {@code invokestatic Minecraft.m_91332_}, whose first instruction dereferences
 * {@code m_91087_().f_91069_} - see {@code client-1.18.2-...-srg.jar}.)
 *
 * <p><b>The fix.</b> Hook {@code Minecraft.crash(CrashReport)} - the only place the original
 * {@code Throwable} still exists - and when the instance is still missing, dump the real exception
 * (including suppressed ones) to both the log and stderr before vanilla's NPE takes over. The
 * launchers (PCL/HMCL) capture stderr verbatim into their raw-output log, so the true cause
 * survives even though no {@code crash-reports/*.txt} is ever written.
 *
 * <p>Normal crashes - where the instance exists - fall through untouched, so nothing changes for
 * the ordinary case. {@code require} is deliberately left at the config default (0) so a future
 * mapping change degrades to a silent no-op instead of refusing to start.
 *
 * <p>NOTE: if this handler stops firing, check that the Mixin annotation processor actually
 * produced {@code mixins.connectormod.refmap.json} for the {@code mod} source set. Without the
 * refmap, Mixin cannot resolve the named target {@code crash} to {@code m_91332_} on an SRG
 * runtime and the injection is silently dropped (require == 0). Verify with
 * {@code tools/check_refmaps.py}.
 */
@Mixin(Minecraft.class)
public class MinecraftCrashDumpMixin {

    private static final Logger CONNECTOR_LOGGER = LoggerFactory.getLogger("SinytraConnector/CrashDump");

    @Inject(method = "crash", at = @At("HEAD"))
    private static void connector$dumpSwallowedInitCrash(CrashReport report, CallbackInfo ci) {
        if (Minecraft.getInstance() != null) {
            return; // Ordinary crash: Forge/vanilla will report it properly.
        }

        Throwable cause = report.getException();
        String header = """
            
            ============ Sinytra Connector: the REAL cause of this boot failure ============
            Main#main's catch block calls Minecraft.getInstance().crash(crashReport), but the
            Minecraft instance could not be created, so getInstance() == null and the handler
            throws its own NPE - hiding the actual exception. Here is the original throwable
            that was stored in the CrashReport:
            -------------------------------------------------------------------------------""";
        CONNECTOR_LOGGER.error(header, cause);

        // Second channel: launchers (PCL / HMCL) copy stderr verbatim into their raw log.
        System.err.println(header);
        cause.printStackTrace(System.err);
        for (Throwable suppressed : cause.getSuppressed()) {
            suppressed.printStackTrace(System.err);
        }
        System.err.flush();
    }
}
