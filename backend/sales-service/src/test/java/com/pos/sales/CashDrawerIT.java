package com.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.pos.common.error.ApiException;
import com.pos.events.EventJson;
import com.pos.events.payments.PaymentMethod;
import com.pos.sales.domain.Cart;
import com.pos.sales.domain.Sale;
import com.pos.sales.domain.SaleStatus;
import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.cash.CashCount;
import com.pos.sales.service.CartService;
import com.pos.sales.service.CashDrawerService;
import com.pos.sales.service.CashLimitService;
import com.pos.sales.service.CheckoutService;
import com.pos.sales.service.IntradayService;
import com.pos.sales.service.RegisterService;
import com.pos.sales.service.TillSessionService;

/**
 * The drawer by note and coin: tills numbered per branch, change made from what the drawer holds,
 * deposits and replenishments through the branch's intraday cash, cash limits, and the closing
 * count.
 */
@AutoConfigureMockMvc
@DisplayName("Tills, the drawer and intraday cash")
class CashDrawerIT extends SalesTestBase {

    @Autowired private TillSessionService tills;
    @Autowired private CartService carts;
    @Autowired private CheckoutService checkout;
    @Autowired private CashDrawerService drawer;
    @Autowired private IntradayService intraday;
    @Autowired private CashLimitService limits;
    @Autowired private RegisterService registers;
    @Autowired private MockMvc mockMvc;

    private static List<CashCount.Line> notes(int... pairs) {
        List<CashCount.Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            lines.add(new CashCount.Line(BigDecimal.valueOf(pairs[i]), pairs[i + 1]));
        }
        return lines;
    }

    private static CashCount cash(int... pairs) {
        return CashCount.of(notes(pairs));
    }

    /** A shift at {@code branch} whose float is counted note by note. */
    private TillSession trackedShift(UUID branch, int... floatNotes) {
        CashCount counted = cash(floatNotes);
        return tills.open(branch, UUID.randomUUID(), counted.total(), notes(floatNotes));
    }

    /** One juice (250.00) checked out on {@code till}, not yet paid. */
    private Sale juiceSale(TillSession till) {
        Cart cart = carts.open(till.getId(), null, false);
        carts.addLine(cart.getId(), JUICE.id(), JUICE.sku(), null, BigDecimal.ONE, false, TOKEN);
        return checkout.checkout(cart.getId(), null, TOKEN);
    }

    private Sale payCash(Sale sale, int... handedOver) {
        CashCount given = cash(handedOver);
        return checkout.tender(
                sale.getId(),
                List.of(new CheckoutService.Tender(PaymentMethod.CASH, given.total(), null, null)),
                given.total(),
                notes(handedOver));
    }

    private static String code(Throwable failure) {
        return ((ApiException) failure).code();
    }

    // --- tills --------------------------------------------------------------------------

    @Test
    @DisplayName("tills are numbered per branch in order of first use, and can be renamed")
    void tillsAreNumberedPerBranch() {
        UUID branch = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        tills.open(branch, first, BigDecimal.ZERO);
        tills.open(branch, second, BigDecimal.ZERO);

        assertThat(registers.require(first).getNumber()).isEqualTo(1);
        assertThat(registers.require(second).getNumber()).isEqualTo(2);
        // Another branch starts again at 1.
        UUID elsewhere = UUID.randomUUID();
        tills.open(UUID.randomUUID(), elsewhere, BigDecimal.ZERO);
        assertThat(registers.require(elsewhere).getNumber()).isEqualTo(1);

        // A device belongs to its branch.
        assertThatThrownBy(() -> tills.open(UUID.randomUUID(), first, BigDecimal.ZERO))
                .satisfies(failure -> assertThat(code(failure)).isEqualTo("register.other_branch"));

        assertThat(registers.update(second, null, "Express").label()).isEqualTo("Express");
        assertThatThrownBy(() -> registers.update(second, 1, null))
                .satisfies(failure -> assertThat(code(failure)).isEqualTo("register.number_taken"));
        assertThat(registers.update(second, 7, "").label()).isEqualTo("Till 7");
    }

    // --- sales ---------------------------------------------------------------------------

    @Test
    @DisplayName(
            "a cash sale puts the customer's notes in and gives change from what the drawer holds")
    void cashSalesMoveNotesAndMakeChangeFromTheDrawer() {
        TillSession till = trackedShift(BRANCH, 50, 1, 20, 3, 5, 1);

        // 225 paid with 250: 25 back, as a 20 and a 5.
        Sale first = payCash(juiceSale(till), 200, 1, 50, 1);
        assertThat(first.getStatus()).isEqualTo(SaleStatus.PAID);
        assertThat(drawer.changeFor(first.getId())).isEqualTo(cash(20, 1, 5, 1));
        assertThat(drawer.holdings(till.getId())).isEqualTo(cash(200, 1, 50, 2, 20, 2));

        // 75 cannot be made now - there is no 5 left. Refused before any money moves.
        Sale second = juiceSale(till);
        assertThatThrownBy(() -> payCash(second, 100, 3))
                .satisfies(
                        failure -> assertThat(code(failure)).isEqualTo("till.cannot_make_change"));
        assertThat(checkout.require(second.getId()).getStatus()).isNotEqualTo(SaleStatus.PAID);
        assertThat(drawer.holdings(till.getId())).isEqualTo(cash(200, 1, 50, 2, 20, 2));

        // Exact money needs no change.
        Sale paid = payCash(second, 200, 1, 20, 1, 5, 1);
        assertThat(paid.getStatus()).isEqualTo(SaleStatus.PAID);
        assertThat(drawer.holdings(till.getId())).isEqualTo(cash(200, 2, 50, 2, 20, 3, 5, 1));
        assertThat(drawer.holdings(till.getId()).total())
                .isEqualByComparingTo(tills.require(till.getId()).reconcile(null).expectedCash());
    }

    private Sale payCash(Sale sale, List<CashCount.Line> handedOver, List<CashCount.Line> change) {
        BigDecimal given = CashCount.of(handedOver).total();
        return checkout.tender(
                sale.getId(),
                List.of(new CheckoutService.Tender(PaymentMethod.CASH, given, null, null)),
                given,
                handedOver,
                change);
    }

    @Test
    @DisplayName(
            "the cashier chooses the change; it goes ahead only if it tallies and is in the drawer")
    void theCashierChoosesTheChangeAndItIsChecked() {
        TillSession till = trackedShift(BRANCH, 20, 2, 10, 5, 5, 5);
        Sale sale = juiceSale(till); // 225, paid with 250: 25 due

        // 20 does not tally with 25: refused, and nothing moves.
        assertThatThrownBy(() -> payCash(sale, notes(200, 1, 50, 1), notes(10, 2)))
                .satisfies(failure -> assertThat(code(failure)).isEqualTo("till.change_mismatch"));
        // 25 in 1-shilling coins tallies, but the drawer holds none.
        assertThatThrownBy(() -> payCash(sale, notes(200, 1, 50, 1), notes(1, 25)))
                .satisfies(
                        failure ->
                                assertThat(code(failure)).isEqualTo("till.change_not_in_drawer"));
        assertThat(checkout.require(sale.getId()).getStatus()).isNotEqualTo(SaleStatus.PAID);
        assertThat(drawer.holdings(till.getId())).isEqualTo(cash(20, 2, 10, 5, 5, 5));

        // The cashier's way - two 10s and a 5, not the 20 and 5 the till would pick - is accepted.
        Sale paid = payCash(sale, notes(200, 1, 50, 1), notes(10, 2, 5, 1));
        assertThat(paid.getStatus()).isEqualTo(SaleStatus.PAID);
        assertThat(drawer.changeFor(paid.getId())).isEqualTo(cash(10, 2, 5, 1));
        assertThat(drawer.holdings(till.getId()))
                .isEqualTo(cash(200, 1, 50, 1, 20, 2, 10, 3, 5, 4));
    }

    @Test
    @DisplayName(
            "an exchange swaps notes for notes of the same total, and the till's money is unchanged")
    void anExchangeChangesTheNotesNotTheTotal() {
        TillSession till = trackedShift(BRANCH, 500, 2, 100, 10);
        BigDecimal before = tills.require(till.getId()).reconcile(null).expectedCash();

        // Someone breaks a 1000 into two 500s.
        tills.exchange(till.getId(), notes(1000, 1), notes(500, 2));
        assertThat(drawer.holdings(till.getId())).isEqualTo(cash(1000, 1, 100, 10));
        // ...and the 1000 back into ten 100s.
        tills.exchange(till.getId(), notes(100, 10), notes(1000, 1));
        assertThat(drawer.holdings(till.getId())).isEqualTo(cash(100, 20));
        assertThat(tills.require(till.getId()).reconcile(null).expectedCash())
                .isEqualByComparingTo(before);
        assertThat(drawer.holdings(till.getId()).total()).isEqualByComparingTo(before);

        assertThatThrownBy(() -> tills.exchange(till.getId(), notes(1000, 1), notes(500, 1)))
                .satisfies(
                        failure -> assertThat(code(failure)).isEqualTo("till.exchange_unbalanced"));
        assertThatThrownBy(() -> tills.exchange(till.getId(), notes(100, 1), notes(50, 2)))
                .satisfies(
                        failure ->
                                assertThat(code(failure)).isEqualTo("till.exchange_not_in_drawer"));

        // A shift kept by total alone has no notes to exchange.
        TillSession untracked = tills.open(BRANCH, UUID.randomUUID(), new BigDecimal("1000"));
        assertThatThrownBy(() -> tills.exchange(untracked.getId(), notes(1000, 1), notes(500, 2)))
                .satisfies(failure -> assertThat(code(failure)).isEqualTo("till.not_tracked"));

        // Only the cashier on the shift, or a supervisor.
        actingAs(UUID.randomUUID(), CASHIER_PERMISSIONS);
        assertThatThrownBy(() -> tills.exchange(till.getId(), notes(100, 1), notes(100, 1)))
                .satisfies(failure -> assertThat(code(failure)).isEqualTo("till.not_your_shift"));
    }

    @Test
    @DisplayName("notes counted that do not add up to the cash tendered are refused")
    void notesMustAddUpToTheTender() {
        TillSession till = trackedShift(BRANCH, 50, 2);
        Sale sale = juiceSale(till);
        assertThatThrownBy(
                        () ->
                                checkout.tender(
                                        sale.getId(),
                                        List.of(
                                                new CheckoutService.Tender(
                                                        PaymentMethod.CASH,
                                                        new BigDecimal("300"),
                                                        null,
                                                        null)),
                                        new BigDecimal("300"),
                                        notes(200, 1)))
                .satisfies(
                        failure -> assertThat(code(failure)).isEqualTo("cash.received_mismatch"));
    }

    @Test
    @DisplayName("a void pays the cash back out of the drawer in notes it holds")
    void aVoidPaysOutOfTheDrawer() {
        TillSession till = trackedShift(BRANCH, 50, 2, 20, 5, 5, 2);
        Sale sale = payCash(juiceSale(till), 200, 1, 50, 1);
        actingAs(SUPERVISOR, SUPERVISOR_PERMISSIONS);
        checkout.voidSale(sale.getId(), "Customer changed their mind", SUPERVISOR);
        // 225 back out in notes the drawer holds; what remains is the 210 float.
        assertThat(drawer.holdings(till.getId()).total()).isEqualByComparingTo("210");
    }

    // --- intraday -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "deposits go from the drawer to intraday; replenishments only hand over what intraday holds")
    void depositsAndReplenishmentsGoThroughIntraday() {
        UUID branch = UUID.randomUUID();
        TillSession till = trackedShift(branch, 1000, 2, 20, 1);

        tills.recordDrop(
                till.getId(), new BigDecimal("1000"), "Over the limit", null, notes(1000, 1));
        assertThat(drawer.holdings(till.getId())).isEqualTo(cash(1000, 1, 20, 1));
        assertThat(intraday.holdings(branch)).isEqualTo(cash(1000, 1));
        assertThat(tills.require(till.getId()).reconcile(null).expectedCash())
                .isEqualByComparingTo("1020");

        // Only notes the drawer holds can be deposited.
        assertThatThrownBy(
                        () ->
                                tills.recordDrop(
                                        till.getId(),
                                        new BigDecimal("500"),
                                        "x",
                                        null,
                                        notes(500, 1)))
                .satisfies(
                        failure -> assertThat(code(failure)).isEqualTo("till.drop_not_in_drawer"));

        // Intraday has no coins yet.
        assertThatThrownBy(() -> tills.replenish(till.getId(), notes(20, 2), null))
                .satisfies(failure -> assertThat(code(failure)).isEqualTo("intraday.insufficient"));
        intraday.topUp(branch, cash(20, 10, 10, 10), "From the bank");
        tills.replenish(till.getId(), notes(20, 2, 10, 3), null);

        assertThat(drawer.holdings(till.getId())).isEqualTo(cash(1000, 1, 20, 3, 10, 3));
        assertThat(intraday.holdings(branch)).isEqualTo(cash(1000, 1, 20, 8, 10, 7));
        assertThat(tills.require(till.getId()).reconcile(null).expectedCash())
                .isEqualByComparingTo("1090");

        intraday.bank(branch, cash(1000, 1), "To the bank");
        assertThatThrownBy(() -> intraday.bank(branch, cash(1000, 1), "Again"))
                .satisfies(failure -> assertThat(code(failure)).isEqualTo("intraday.insufficient"));
    }

    // --- limits --------------------------------------------------------------------------

    @Test
    @DisplayName("past the limit a till is warned; at the ceiling it takes no cash, but still card")
    void cashLimitsWarnThenBlockCash() {
        UUID branch = UUID.randomUUID();
        limits.set(branch, null, new BigDecimal("300"), new BigDecimal("400"));
        TillSession till = tills.open(branch, UUID.randomUUID(), new BigDecimal("100"));
        assertThat(limits.standing(till).state()).isEqualTo(CashLimitService.State.OK);

        Sale first = juiceSale(till);
        checkout.tender(
                first.getId(),
                List.of(
                        new CheckoutService.Tender(
                                PaymentMethod.CASH, first.getGrandTotal(), null, null)),
                null);
        TillSession after = tills.require(till.getId());
        assertThat(limits.standing(after).state()).isEqualTo(CashLimitService.State.WARN);

        Sale second = juiceSale(after);
        assertThatThrownBy(
                        () ->
                                checkout.tender(
                                        second.getId(),
                                        List.of(
                                                new CheckoutService.Tender(
                                                        PaymentMethod.CASH,
                                                        second.getGrandTotal(),
                                                        null,
                                                        null)),
                                        null))
                .satisfies(
                        failure -> assertThat(code(failure)).isEqualTo("till.over_cash_ceiling"));
        Sale byCard =
                checkout.tender(
                        second.getId(),
                        List.of(
                                new CheckoutService.Tender(
                                        PaymentMethod.CARD, second.getGrandTotal(), null, "T-1")),
                        null);
        assertThat(byCard.getStatus()).isEqualTo(SaleStatus.AWAITING_PAYMENT);

        // A trusted cashier's own limit wins over the branch's.
        limits.set(branch, CASHIER, new BigDecimal("5000"), null);
        assertThat(limits.standing(tills.require(till.getId())).state())
                .isEqualTo(CashLimitService.State.OK);
        assertThat(limits.effective(branch, CASHIER).orElseThrow().getCeilingAmount())
                .isEqualByComparingTo("6000");
    }

    // --- close ---------------------------------------------------------------------------

    @Test
    @DisplayName("the close is counted note by note beside what the drawer should hold")
    void theCloseIsCountedByDenomination() {
        TillSession till = trackedShift(BRANCH, 100, 2);
        tills.beginClose(till.getId());
        TillSession closed = tills.close(till.getId(), null, "End of day", notes(100, 1, 50, 1));

        assertThat(closed.getCountedCash()).isEqualByComparingTo("150");
        assertThat(closed.getVariance()).isEqualByComparingTo("-50");
        Map<BigDecimal, int[]> lines = new java.util.HashMap<>();
        drawer.closingCount(till.getId())
                .forEach(
                        line ->
                                lines.put(
                                        line.getDenomination().stripTrailingZeros(),
                                        new int[] {line.getExpected(), line.getCounted()}));
        assertThat(lines.get(new BigDecimal("1E+2"))).containsExactly(2, 1);
        assertThat(lines.get(new BigDecimal("5E+1"))).containsExactly(0, 1);
    }

    // --- over HTTP -------------------------------------------------------------------------

    private static RequestPostProcessor at(UUID user, String... permissions) {
        return jwt().jwt(
                        Jwt.withTokenValue("test")
                                .header("alg", "RS256")
                                .subject(user.toString())
                                .claim("uid", user.toString())
                                .claim("perms", List.of(permissions))
                                .claim("branches", List.of(BRANCH.toString()))
                                .build())
                .authorities(
                        Arrays.stream(permissions)
                                .map(SimpleGrantedAuthority::new)
                                .toArray(GrantedAuthority[]::new));
    }

    @Test
    @DisplayName(
            "over HTTP: the lane opens with a counted float, sees its drawer and till number; a cashier cannot replenish itself")
    void overHttp() throws Exception {
        UUID register = UUID.randomUUID();
        String opened =
                mockMvc.perform(
                                post("/api/v1/till-sessions")
                                        .with(at(CASHIER, CASHIER_PERMISSIONS))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                EventJson.write(
                                                        Map.of(
                                                                "branchId",
                                                                BRANCH,
                                                                "registerId",
                                                                register,
                                                                "openingFloat",
                                                                150,
                                                                "floatCount",
                                                                List.of(
                                                                        Map.of(
                                                                                "denomination",
                                                                                100,
                                                                                "count",
                                                                                1),
                                                                        Map.of(
                                                                                "denomination",
                                                                                50,
                                                                                "count",
                                                                                1))))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.tracksDenominations", is(true)))
                        .andExpect(
                                jsonPath("$.tillLabel", org.hamcrest.Matchers.startsWith("Till ")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String session = EventJson.mapper().readTree(opened).get("id").asString();

        mockMvc.perform(
                        get("/api/v1/till-sessions/" + session + "/drawer")
                                .with(at(CASHIER, CASHIER_PERMISSIONS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdings", hasSize(2)))
                .andExpect(jsonPath("$.holdings[0].denomination", is(100)))
                .andExpect(jsonPath("$.holdings[0].note", is(true)))
                .andExpect(jsonPath("$.countedTotal", is(150)))
                .andExpect(jsonPath("$.limitState", is("OK")));

        mockMvc.perform(
                        post("/api/v1/till-sessions/" + session + "/replenishments")
                                .with(at(CASHIER, CASHIER_PERMISSIONS))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"notes\":[{\"denomination\":20,\"count\":1}]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        get("/api/v1/intraday")
                                .param("branchId", BRANCH.toString())
                                .with(at(CASHIER, CASHIER_PERMISSIONS)))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        get("/api/v1/cash-limits")
                                .param("branchId", BRANCH.toString())
                                .with(at(CASHIER, CASHIER_PERMISSIONS)))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        get("/api/v1/intraday")
                                .param("branchId", BRANCH.toString())
                                .with(at(SUPERVISOR, "cash:intraday")))
                .andExpect(status().isOk());
        mockMvc.perform(
                        get("/api/v1/registers")
                                .param("branchId", BRANCH.toString())
                                .with(at(SUPERVISOR, "till:manage")))
                .andExpect(status().isOk());
    }
}
