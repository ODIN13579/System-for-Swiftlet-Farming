package com.swiftletcare.ai;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "count_logs", schema = "swiftletcare")
public class CountLog {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long deviceId;
  public Instant periodStart;
  public Instant periodEnd;
  public Integer entryCount;
  public Integer exitCount;
  public Integer detectCount;
  public String aggregationType;
  public Instant processedAt;
}
