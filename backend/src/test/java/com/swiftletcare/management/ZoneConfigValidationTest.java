package com.swiftletcare.management;

import static org.assertj.core.api.Assertions.*;
import com.swiftletcare.api.ApiError;
import org.junit.jupiter.api.Test;

class ZoneConfigValidationTest {
  @Test void everyThresholdRejectsNonFiniteNumbers() {
    for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
      for(int field=0;field<9;field++) {
        Double[] values={74d,78d,20d,31d,1d,400d,2000d,0d,100d}; values[field]=bad;
        var p=new ZoneConfigController.Input(values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],values[8],5,2,0);
        assertThatThrownBy(()->ZoneConfigController.validate(p)).isInstanceOf(ApiError.Failure.class);
      }
    }
  }
}
