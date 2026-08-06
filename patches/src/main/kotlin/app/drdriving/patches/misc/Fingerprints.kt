package app.drdriving.patches.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * d.buyCar(I)V — private car purchase (coins AND gold).
 * Coin branch (negative iPrice): affordability check + iCoinC/iCoin spend.
 * Confirmed smali: d.smali:1036. Filters land on the coin branch.
 * Last filter (iCoinC) is the spend read; SUB_INT (matches[3]) is the
 * affordability check where the price (v1) gets zeroed to force free buy.
 */
object BuyCarFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/d;",
    name = "buyCar",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("I"),
    filters = listOf(
        fieldAccess(smali = "Lcom/ansangha/drdriving/DrDrivingActivity;->mSaveGame:Lcom/ansangha/drdriving/o;"),
        fieldAccess(smali = "Lcom/ansangha/drdriving/o;->iCoin:I"),
        fieldAccess(smali = "Lcom/ansangha/drdriving/DrDrivingActivity;->rs:I"),
        opcode(Opcode.SUB_INT_2ADDR),
        fieldAccess(smali = "Lcom/ansangha/drdriving/o;->iCoinC:I")
    )
)

/**
 * d.upgradeCar(II)V — private car upgrade spend.
 * Confirmed smali: d.smali:29148. Cost register v0 (iGetUpgradeCost result).
 * Zeroing v0 at the affordability SUB_INT makes upgrades free.
 */
object UpgradeCarFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/d;",
    name = "upgradeCar",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("I", "I"),
    filters = listOf(
        fieldAccess(smali = "Lcom/ansangha/drdriving/DrDrivingActivity;->mSaveGame:Lcom/ansangha/drdriving/o;"),
        fieldAccess(smali = "Lcom/ansangha/drdriving/o;->iCoin:I"),
        fieldAccess(smali = "Lcom/ansangha/drdriving/DrDrivingActivity;->rs:I"),
        opcode(Opcode.SUB_INT_2ADDR),
        fieldAccess(smali = "Lcom/ansangha/drdriving/o;->iCoinC:I")
    )
)

/**
 * d.rentCar(I)V — private car rental (coins AND gold).
 * Confirmed smali: d.smali:28549. Cost register v0 (after neg-int).
 * Zeroing v0 at the affordability SUB_INT makes rentals free.
 */
object RentCarFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/d;",
    name = "rentCar",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("I"),
    filters = listOf(
        fieldAccess(smali = "Lcom/ansangha/drdriving/o;->iCarRent:[I"),
        fieldAccess(smali = "Lcom/ansangha/drdriving/o;->iCoin:I"),
        fieldAccess(smali = "Lcom/ansangha/drdriving/DrDrivingActivity;->rs:I"),
        opcode(Opcode.SUB_INT_2ADDR),
        fieldAccess(smali = "Lcom/ansangha/drdriving/o;->iCoinC:I")
    )
)

/**
 * d.processCarSelect()V — repair spend on the car-select screen.
 * Confirmed smali: d.smali:8251, repair block ~8511. Cost register v0
 * (iGetRepairCost result). Zeroing v0 at the affordability SUB_INT makes
 * repairs free.
 */
object ProcessCarSelectRepairFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/d;",
    name = "processCarSelect",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(smali = "Lcom/ansangha/drdriving/o;->iCarDamage:[I"),
        methodCall(definingClass = "Lcom/ansangha/drdriving/d;", name = "iGetRepairCost"),
        opcode(Opcode.SUB_INT_2ADDR),
        fieldAccess(smali = "Lcom/ansangha/drdriving/o;->iCoinC:I")
    )
)

/**
 * d.processMultiEnding()V — repair spend on the multiplayer results screen.
 * Confirmed smali: d.smali:9574, repair block ~10238. Same shape as
 * ProcessCarSelectRepairFingerprint.
 */
object ProcessMultiEndingRepairFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/d;",
    name = "processMultiEnding",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(smali = "Lcom/ansangha/drdriving/o;->iCarDamage:[I"),
        methodCall(definingClass = "Lcom/ansangha/drdriving/d;", name = "iGetRepairCost"),
        opcode(Opcode.SUB_INT_2ADDR),
        fieldAccess(smali = "Lcom/ansangha/drdriving/o;->iCoinC:I")
    )
)

/**
 * DrDrivingActivity.loadLocal()V — private save-load from SharedPreferences.
 * Confirmed smali: DrDrivingActivity.smali:1028. Runs on every app start
 * (called from onCreate at smali:6490). Builds a fresh o(prefs, "game") and
 * sput-objects it to mSaveGame.
 *
 * The last filter (fieldAccess mSaveGame) matches that sput-object. Injecting
 * right AFTER it (index + 1) inflates the freshly loaded save before the
 * update loop reads balances — no store interaction needed.
 */
object LoadLocalFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;",
    name = "loadLocal",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Landroid/app/Activity;", name = "getPreferences"),
        methodCall(definingClass = "Lcom/ansangha/drdriving/o;", name = "<init>"),
        fieldAccess(smali = "Lcom/ansangha/drdriving/DrDrivingActivity;->mSaveGame:Lcom/ansangha/drdriving/o;")
    )
)

/**
 * DrDrivingActivity.CheckAd()V — per-frame update loop.
 * Confirmed smali: DrDrivingActivity.smali:2365. Contains the gold
 * anti-cheat gate: if (iBoughtGold > 15000) OR (iGold - rs > 15000) the
 * game calls finish() and exits (verified: gate at smali:2419-2442 lands on
 * finish() at smali:3611). The literal 0x3a98 (15000) appears only once in
 * the class — it is the gate threshold register v3. Inserting a larger const
 * right after it raises the cap so inflated balances never trip the exit.
 */
object CheckAdAntiCheatFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;",
    name = "CheckAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        literal(0x3a98),
        opcode(Opcode.IF_GT),
        fieldAccess(smali = "Lcom/ansangha/drdriving/o;->iGold:I"),
        opcode(Opcode.GOTO_16)
    )
)

/**
 * DrDrivingActivity.GoToPurchase(I)V — store gold-buy launcher.
 * Confirmed smali: DrDrivingActivity.smali:4002. Only reachable from the
 * gold-buy confirm path (g_iRequestPurchase > 0 in the update loop).
 * Filters: isSignedIn gate + SKU strings.
 */
object GoToPurchaseFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;",
    name = "GoToPurchase",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I"),
    filters = listOf(
        methodCall(definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;", name = "isSignedIn"),
        string("drdriving.gold200"),
        string("drdriving.gold1500")
    )
)

/**
 * DrDrivingActivity.onConsumeResponse(Lg;Ljava/lang/String;)V — gold grant
 * point after a Play Billing consume succeeds. Confirmed smali:
 * DrDrivingActivity.smali:6098. Stable interface-override method name.
 * Filters in instruction order: BillingResult.getResponseCode (renamed g->c),
 * gold600 string, String.equals, iGoldC read, 0x258 (600) literal,
 * SaveSettings.
 */
object OnConsumeResponseFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;",
    name = "onConsumeResponse",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Lcom/android/billingclient/api/g;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/android/billingclient/api/g;", name = "c"),
        string("drdriving.gold600"),
        methodCall(definingClass = "Ljava/lang/String;", name = "equals"),
        fieldAccess(smali = "Lcom/ansangha/drdriving/o;->iGoldC:I"),
        literal(0x258),
        methodCall(definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;", name = "SaveSettings")
    )
)
