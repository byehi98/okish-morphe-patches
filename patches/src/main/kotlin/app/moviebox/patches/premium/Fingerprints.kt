package app.moviebox.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.transsion.memberapi.MemberInfo.isActive()Z
 *
 * Kotlin data-class getter for the master "is a paying member" flag. Every
 * membership gate (MemberProvider, PremiumProvider, player quality dialog,
 * download rights) reads this bean. Stable class + method names (memberapi
 * module is not obfuscated).
 */
object MemberInfoIsActiveFingerprint : Fingerprint(
    definingClass = "Lcom/transsion/memberapi/MemberInfo;",
    name = "isActive",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
)

/**
 * com.transsion.memberapi.MemberInfo.getVipLevel()I
 *
 * VIP tier. Level 1 = Premium Pro. Forces the highest tier when combined
 * with the isActive patch.
 */
object MemberInfoVipLevelFingerprint : Fingerprint(
    definingClass = "Lcom/transsion/memberapi/MemberInfo;",
    name = "getVipLevel",
    returnType = "I",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
)

/**
 * com.transsion.member.MemberProvider.c()Z — IMemberApi member-active check.
 *
 * Returns memberDetail.getMemberInfo().isActive() (false when memberDetail
 * is null, i.e. non-member). Patching this covers the player config dialog
 * ("is_member") and other consumers even when the server sends no member
 * record. Method name obfuscated ("c") but stable for this version; class is
 * not obfuscated.
 */
object MemberProviderIsMemberFingerprint : Fingerprint(
    definingClass = "Lcom/transsion/member/MemberProvider;",
    name = "c",
    returnType = "Z"
)

/**
 * com.transsion.member.premium.PremiumProvider.c()Z — IPremiumApi active
 * check, same memberDetail.getMemberInfo().isActive() pattern.
 */
object PremiumProviderIsActiveFingerprint : Fingerprint(
    definingClass = "Lcom/transsion/member/premium/PremiumProvider;",
    name = "c",
    returnType = "Z"
)

/**
 * com.transsion.member.premium.PremiumProvider.k()Z — IPremiumApi Pro check,
 * returns true only when memberInfo.getVipLevel() == 1.
 */
object PremiumProviderIsProFingerprint : Fingerprint(
    definingClass = "Lcom/transsion/member/premium/PremiumProvider;",
    name = "k",
    returnType = "Z"
)

/**
 * com.transsion.member.premium.PremiumProvider.o()Ljava/lang/Integer; —
 * IPremiumApi.mo71619o(), days-left-until-expiry fed into the member guide
 * banner (MemberGuideBannerView.setMemberInfo). Returns null for non-members,
 * which the banner treats as 0 → "premium ends in 0 days". Returning 30 makes
 * the banner take the "expiry far away" branch instead.
 */
object PremiumDaysLeftFingerprint : Fingerprint(
    definingClass = "Lcom/transsion/member/premium/PremiumProvider;",
    name = "o",
    returnType = "Ljava/lang/Integer;"
)

/**
 * com.transsion.member.premium.PremiumProvider.u()Z — IPremiumApi.mo71625u(),
 * isProMember flag also fed to the member guide banner. Combined with the
 * daysLeft patch this pushes the banner into its hidden branch
 * (setVisibility(GONE)), removing the "Upgrade to Premium Pro" / "Premium
 * ends in X days" banners from detail pages.
 */
object PremiumIsProMemberFingerprint : Fingerprint(
    definingClass = "Lcom/transsion/member/premium/PremiumProvider;",
    name = "u",
    returnType = "Z"
)

/**
 * com.transsion.member.MemberProvider.f()Z — IMemberApi.mo71194o(),
 * kv_is_pay_enable_member MMKV flag (default false).
 *
 * Gates the member payment entry surface (pay-enable UI). The mod forces it
 * true; returning true surfaces the payment/member entry. Class is stable;
 * method name ("f") is obfuscated but pinned to this version, and the kv key
 * string makes the match unambiguous.
 */
object MemberProviderPayEnableFingerprint : Fingerprint(
    definingClass = "Lcom/transsion/member/MemberProvider;",
    name = "f",
    returnType = "Z",
    filters = listOf(
        string("kv_is_pay_enable_member")
    )
)
