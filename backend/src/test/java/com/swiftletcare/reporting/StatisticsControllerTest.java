package com.swiftletcare.reporting;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.security.Access;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

class StatisticsControllerTest {
  private final Access access = mock(Access.class);
  private final JdbcTemplate db = mock(JdbcTemplate.class);
  private final StatisticsController controller = new StatisticsController(access, db);
  private final Jwt jwt = mock(Jwt.class);
  private final Instant from = Instant.parse("2026-07-22T17:00:00Z");
  private final Instant to = from.plusSeconds(3600);

  @Test void rejectsEmptyRangeBeforeAccessOrQuery() {
    assertThatThrownBy(() -> controller.environment(jwt, 1, null, from, from,
        StatisticsController.Granularity.HOURLY, null)).isInstanceOf(ApiError.Failure.class);
    verifyNoInteractions(access, db);
  }

  @Test void deniesExportWhenUserCannotReadRequestedHouse() {
    doThrow(new ApiError.Failure(403, "ACCESS_DENIED", "No House access"))
        .when(access).requireHouse(jwt, 2, Access.Permission.READ);
    assertThatThrownBy(() -> controller.birdCountCsv(jwt, 1, 2L, from, to,
        StatisticsController.Granularity.DAILY)).isInstanceOf(ApiError.Failure.class);
    verify(access).requireZone(jwt, 1, Access.Permission.READ);
    verifyNoInteractions(db);
  }
}
