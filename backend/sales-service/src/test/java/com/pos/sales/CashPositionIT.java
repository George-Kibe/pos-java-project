package com.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.pos.common.security.AuthenticatedUser;
import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.cash.CashCount;
import com.pos.sales.service.CashPositionService;
import com.pos.sales.service.IntradayService;
import com.pos.sales.service.TillSessionService;

/**
 * Where a branch's cash is: each till still on a shift, the intraday cash the supervisors hold, and
 * the total - for a branch's own supervisors, and for the administrator across every branch.
 */
@AutoConfigureMockMvc
@DisplayName("The cash position of a branch")
class CashPositionIT extends SalesTestBase {

    @Autowired private TillSessionService tills;
    @Autowired private IntradayService intraday;
    @Autowired private CashPositionService positions;
    @Autowired private MockMvc mockMvc;

    private static CashCount cash(int... pairs) {
        List<CashCount.Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            lines.add(new CashCount.Line(BigDecimal.valueOf(pairs[i]), pairs[i + 1]));
        }
        return CashCount.of(lines);
    }

    private static RequestPostProcessor at(UUID user, List<UUID> branches, String... permissions) {
        return jwt().jwt(
                        Jwt.withTokenValue("test")
                                .header("alg", "RS256")
                                .subject(user.toString())
                                .claim("uid", user.toString())
                                .claim("perms", List.of(permissions))
                                .claim("branches", branches.stream().map(UUID::toString).toList())
                                .build())
                .authorities(
                        Arrays.stream(permissions)
                                .map(SimpleGrantedAuthority::new)
                                .toArray(GrantedAuthority[]::new));
    }

    @Test
    @DisplayName(
            "counts each till's cash and the intraday once each; a till handed over holds nothing")
    void aBranchsCashIsItsTillsAndItsIntraday() {
        UUID branch = UUID.randomUUID();
        TillSession first = tills.open(branch, UUID.randomUUID(), new BigDecimal("1500"));
        TillSession second = tills.open(branch, UUID.randomUUID(), new BigDecimal("700"));
        TillSession closing = tills.open(branch, UUID.randomUUID(), new BigDecimal("300"));
        intraday.topUp(branch, cash(1000, 5), "Opening balance");

        // The third till's 300 goes to the supervisor: it is now intraday cash, not the till's.
        handOver(closing.getId(), new BigDecimal("300"));

        CashPositionService.BranchPosition position = positions.of(branch);
        assertThat(position.tills()).hasSize(3);
        assertThat(
                        position.tills().stream()
                                .filter(till -> till.session().getId().equals(first.getId()))
                                .findFirst()
                                .orElseThrow()
                                .held())
                .isEqualByComparingTo("1500");
        assertThat(
                        position.tills().stream()
                                .filter(till -> till.session().getId().equals(closing.getId()))
                                .findFirst()
                                .orElseThrow()
                                .held())
                .isEqualByComparingTo("0");
        assertThat(position.tillsTotal()).isEqualByComparingTo("2200");
        assertThat(position.intradayTotal()).isEqualByComparingTo("5300");
        assertThat(position.total()).isEqualByComparingTo("7500");

        // A closed shift's till holds nothing any more.
        actingAs(CASHIER, CASHIER_PERMISSIONS);
        tills.close(closing.getId(), null, null);
        assertThat(positions.of(branch).tills()).hasSize(2);
        assertThat(positions.of(branch).total()).isEqualByComparingTo("7500");
        assertThat(positions.of(branch).tills())
                .extracting(till -> till.session().getId())
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    @DisplayName("a supervisor sees their own branches; the administrator every branch with cash")
    void whoSeesWhichBranches() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        tills.open(mine, UUID.randomUUID(), new BigDecimal("100"));
        intraday.topUp(theirs, cash(500, 2), "Opening balance");

        AuthenticatedUser supervisor =
                new AuthenticatedUser(
                        SUPERVISOR,
                        null,
                        java.util.Set.of(),
                        java.util.Set.of("cash:intraday"),
                        java.util.Set.of(mine),
                        0);
        assertThat(positions.forCaller(supervisor))
                .extracting(CashPositionService.BranchPosition::branchId)
                .containsExactly(mine);

        AuthenticatedUser administrator =
                new AuthenticatedUser(
                        UUID.randomUUID(),
                        null,
                        java.util.Set.of(),
                        java.util.Set.of("cash:intraday", "branch:access:all"),
                        java.util.Set.of(),
                        0);
        assertThat(positions.forCaller(administrator))
                .extracting(CashPositionService.BranchPosition::branchId)
                .contains(mine, theirs);
    }

    @Test
    @DisplayName(
            "over HTTP: supervisors of the branch and the administrator; not cashiers, not another"
                    + " branch's supervisor")
    void overHttp() throws Exception {
        tills.open(BRANCH, UUID.randomUUID(), new BigDecimal("2000"));
        intraday.topUp(BRANCH, cash(1000, 3), "Opening balance");

        mockMvc.perform(
                        get("/api/v1/cash-positions/" + BRANCH)
                                .with(at(SUPERVISOR, List.of(BRANCH), "cash:intraday")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tills", hasSize(1)))
                .andExpect(jsonPath("$.tills[0].held").value(2000.0))
                .andExpect(jsonPath("$.tills[0].cashierId", is(CASHIER.toString())))
                .andExpect(jsonPath("$.intradayTotal").value(3000.0))
                .andExpect(jsonPath("$.total").value(5000.0))
                .andExpect(jsonPath("$.currency", is("KES")));
        mockMvc.perform(
                        get("/api/v1/cash-positions")
                                .with(at(SUPERVISOR, List.of(BRANCH), "cash:intraday")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].openTills", is(1)))
                .andExpect(jsonPath("$[0].total").value(5000.0));

        mockMvc.perform(
                        get("/api/v1/cash-positions/" + BRANCH)
                                .with(at(CASHIER, List.of(BRANCH), CASHIER_PERMISSIONS)))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        get("/api/v1/cash-positions/" + BRANCH)
                                .with(
                                        at(
                                                UUID.randomUUID(),
                                                List.of(UUID.randomUUID()),
                                                "cash:intraday")))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        get("/api/v1/cash-positions/" + BRANCH)
                                .with(
                                        at(
                                                UUID.randomUUID(),
                                                List.of(),
                                                "cash:intraday",
                                                "branch:access:all")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5000.0));
    }
}
