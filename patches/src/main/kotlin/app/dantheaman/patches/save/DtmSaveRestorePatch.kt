package app.dantheaman.patches.save

import app.dantheaman.patches.shared.Constants.COMPATIBILITY_DTM
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/**
 * Dan the Man — Unlimited money (save injection), trigger + restorer.
 *
 * Injects a save-restorer as a new static method on MortarApplication and calls
 * it right after invoke-super in Application#onCreate, before any game SDK
 * starts. On first run it expands the embedded assets/actions.online bundle
 * (see DtmActionsOnlineAssetPatch) into the app's internal storage, which is
 * where the native Mortar engine keeps its AES-encrypted saves:
 *
 *   /data/data/com.halfbrick.dantheman/files/Dan_The_Man/save/Purchases.json
 *   /data/data/com.halfbrick.dantheman/files/Dan_The_Man/save/Game/.../UserData.json
 *   /data/data/com.halfbrick.dantheman/files/Dan_The_Man/log_gold.txt
 */
@Suppress("unused")
val dtmSaveRestorePatch = bytecodePatch(
    name = "Unlimited money (restore save)",
    description = "Loads the unlimited money save into the game the first time you open it.",
    default = true
) {
    compatibleWith(COMPATIBILITY_DTM)

    execute {
        // ────────────────────────────────────────────────────────────────────
        // 1. Locate MortarApplication.onCreate. The defining class + name are
        //    already exact; the AppsFlyer init call (waitForCustomerUserId) is
        //    unique to this method and keeps the match self-verifying.
        // ────────────────────────────────────────────────────────────────────
        val onCreateFingerprint = object : Fingerprint(
            definingClass = "Lcom/halfbrick/mortar/MortarApplication;",
            accessFlags = listOf(AccessFlags.PUBLIC),
            returnType = "V",
            parameters = listOf(),
            filters = listOf(
                methodCall(
                    definingClass = "Lcom/appsflyer/AppsFlyerLib;",
                    name = "waitForCustomerUserId"
                )
            )
        ) {}

        val onCreate = onCreateFingerprint.method
            ?: throw PatchException("DTM save restore: MortarApplication.onCreate not found — app layout changed?")

        // Index of the invoke-super boundary (everything before it is MultiDex
        // / androidx boilerplate; our hook goes right after it).
        val superIdx = onCreate.indexOfFirstInstructionOrThrow(Opcode.INVOKE_SUPER)

        // ────────────────────────────────────────────────────────────────────
        // 2. Inject the restorer as a new static method on MortarApplication.
        //
        // Java equivalent (register map in brackets):
        //
        //   File marker = new File(ctx.getFilesDir(), "dantheman.save.init");
        //   if (marker.exists()) return;
        //   marker.createNewFile();   // FIRST — a later failure must not loop
        //   File dtm = new File(ctx.getFilesDir(), "Dan_The_Man");
        //   if (dtm.exists()) return;                    // never clobber saves
        //   ZipInputStream zis = new ZipInputStream(
        //       ctx.getAssets().open("actions.online")); // [v0]
        //   File filesDir = ctx.getFilesDir();           // [v3]
        //   byte[] buf = new byte[0x2000];               // [v4]
        //   ZipEntry e;                                  // [v1]
        //   while ((e = zis.getNextEntry()) != null) {
        //       if (e.isDirectory()) { zis.closeEntry(); continue; }
        //       String name = e.getName();               // "files/Dan_The_Man/x"
        //       int slash = name.indexOf('/');
        //       if (slash < 0) { zis.closeEntry(); continue; }
        //       String rel = name.substring(slash + 1);  // "Dan_The_Man/x"
        //       if (rel.isEmpty()) { zis.closeEntry(); continue; }
        //       File out = new File(filesDir, rel);
        //       File parent = out.getParentFile();       // full chain mkdirs
        //       if (parent != null) parent.mkdirs();
        //       OutputStream os = new FileOutputStream(out);
        //       int n; while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
        //       os.close();
        //       zis.closeEntry();
        //   }
        //   zis.close();
        // ────────────────────────────────────────────────────────────────────
        val restorer = ImmutableMethod(
            onCreateFingerprint.definingClass ?: "Lcom/halfbrick/mortar/MortarApplication;",
            "DtmRestoreSaveIfFirstRun",
            listOf(ImmutableMethodParameter("Landroid/content/Context;", null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null, // annotations
            null, // hiddenApiRestrictions
            MutableMethodImplementation(9) // v0-v7 scratch + p0
        ).toMutable().apply {
            addInstructions(0, """
                # ── once-per-install marker (checked, then created FIRST so a
                #    later failure can never crash-loop the app) ──────────────
                new-instance v0, Ljava/io/File;
                invoke-virtual {p0}, Landroid/content/Context;->getFilesDir()Ljava/io/File;
                move-result-object v1
                const-string v2, "dantheman.save.init"
                invoke-direct {v0, v1, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V
                invoke-virtual {v0}, Ljava/io/File;->exists()Z
                move-result v1
                if-eqz v1, :mk_marker
                return-void

                :mk_marker
                invoke-virtual {v0}, Ljava/io/File;->createNewFile()Z
                move-result v1

                # ── never clobber an existing game profile ──────────────────
                new-instance v0, Ljava/io/File;
                invoke-virtual {p0}, Landroid/content/Context;->getFilesDir()Ljava/io/File;
                move-result-object v1
                const-string v2, "Dan_The_Man"
                invoke-direct {v0, v1, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V
                invoke-virtual {v0}, Ljava/io/File;->exists()Z
                move-result v1
                if-eqz v1, :open_zip
                return-void

                # ── open the embedded bundle ────────────────────────────────
                :open_zip
                new-instance v0, Ljava/util/zip/ZipInputStream;
                invoke-virtual {p0}, Landroid/content/Context;->getAssets()Landroid/content/res/AssetManager;
                move-result-object v1
                const-string v2, "actions.online"
                invoke-virtual {v1, v2}, Landroid/content/res/AssetManager;->open(Ljava/lang/String;)Ljava/io/InputStream;
                move-result-object v1
                invoke-direct {v0, v1}, Ljava/util/zip/ZipInputStream;-><init>(Ljava/io/InputStream;)V

                # v3 = filesDir (loop-invariant), v4 = copy buffer
                invoke-virtual {p0}, Landroid/content/Context;->getFilesDir()Ljava/io/File;
                move-result-object v3
                const/16 v4, 0x2000
                new-array v4, v4, [B

                :loop_start
                invoke-virtual {v0}, Ljava/util/zip/ZipInputStream;->getNextEntry()Ljava/util/zip/ZipEntry;
                move-result-object v1
                if-eqz v1, :done

                invoke-virtual {v1}, Ljava/util/zip/ZipEntry;->isDirectory()Z
                move-result v2
                if-eqz v2, :not_dir
                invoke-virtual {v0}, Ljava/util/zip/ZipInputStream;->closeEntry()V
                goto :loop_start

                :not_dir
                invoke-virtual {v1}, Ljava/util/zip/ZipEntry;->getName()Ljava/lang/String;
                move-result-object v1
                const/16 v2, 0x2f
                invoke-virtual {v1, v2}, Ljava/lang/String;->indexOf(I)I
                move-result v2
                if-ltz v2, :skip_entry

                # rel = name.substring(slash + 1) — strip "files/" prefix once
                add-int/lit8 v5, v2, 0x1
                invoke-virtual {v1, v5}, Ljava/lang/String;->substring(I)Ljava/lang/String;
                move-result-object v5
                invoke-virtual {v5}, Ljava/lang/String;->length()I
                move-result v6
                if-lez v6, :skip_entry

                # out = new File(filesDir, rel)
                new-instance v6, Ljava/io/File;
                invoke-direct {v6, v3, v5}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V

                # full parent chain: out.getParentFile().mkdirs()
                invoke-virtual {v6}, Ljava/io/File;->getParentFile()Ljava/io/File;
                move-result-object v5
                if-eqz v5, :no_parent
                invoke-virtual {v5}, Ljava/io/File;->mkdirs()Z
                move-result v5
                :no_parent

                new-instance v5, Ljava/io/FileOutputStream;
                invoke-direct {v5, v6}, Ljava/io/FileOutputStream;-><init>(Ljava/io/File;)V

                :copy_loop
                invoke-virtual {v0, v4}, Ljava/util/zip/ZipInputStream;->read([B)I
                move-result v7
                if-lez v7, :copy_end
                const/4 v6, 0x0
                invoke-virtual {v5, v4, v6, v7}, Ljava/io/FileOutputStream;->write([BII)V
                goto :copy_loop

                :copy_end
                invoke-virtual {v5}, Ljava/io/FileOutputStream;->close()V

                :skip_entry
                invoke-virtual {v0}, Ljava/util/zip/ZipInputStream;->closeEntry()V
                goto :loop_start

                :done
                invoke-virtual {v0}, Ljava/util/zip/ZipInputStream;->close()V
                return-void
            """.trimIndent())
        }

        val mortarAppClass = onCreateFingerprint.classDef
        mortarAppClass.methods.add(restorer)

        // ────────────────────────────────────────────────────────────────────
        // 3. Call it from onCreate right after invoke-super. p0 (the
        //    Application) is a valid Context for getAssets/getFilesDir.
        // ────────────────────────────────────────────────────────────────────
        onCreate.addInstructions(
            superIdx + 1,
            """
            invoke-static {p0}, Lcom/halfbrick/mortar/MortarApplication;->DtmRestoreSaveIfFirstRun(Landroid/content/Context;)V
            """.trimIndent()
        )

        println("DTM save restore: injected DtmRestoreSaveIfFirstRun + call after invoke-super in MortarApplication.onCreate")
    }
}
