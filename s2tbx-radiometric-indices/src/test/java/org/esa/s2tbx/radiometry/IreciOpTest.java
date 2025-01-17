package org.esa.s2tbx.radiometry;

import java.util.HashMap;

/**
 * Created by dmihailescu on 2/10/2016.
 */

public class IreciOpTest extends BaseIndexOpTest<IreciOp> {

    @Override
    public void setUp() throws Exception {
        setupBands(new String[] { "RED (B4)", "RED (B5)", "RED (B6)", "NIR (B7)" }, 3, 3, new float[] { 665, 705, 740, 783 }, new float[] { 1, 2, 3, 4 }, new float[] { 9, 10, 11, 12 });
        setOperatorParameters(new HashMap<String, Float>() {{
            put("redB4Factor", 1.0f);
            put("redB5Factor", 1.0f);
            put("redB6Factor", 1.0f);
            put("nirFactor", 1.0f);
        }});
        setTargetValues(new float[] {
                4.500000f, 4.000000f, 3.750000f,
                3.600000f, 3.500000f, 3.428571f,
                3.375000f, 3.333333f, 3.300000f } );
        super.setUp();
    }
}