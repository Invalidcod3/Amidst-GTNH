package amidst.gtnh.export;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import amidst.gtnh.prospecting.ProspectingData.*;

public class ProspectingExportTest {
    private Deposit fluid() {
        Deposit d = new Deposit(); d.x=-128; d.z=-128; d.id="oil"; d.name="Oil"; d.amounts=new int[64];
        d.amounts[0]=149; d.amounts[1]=150; d.amounts[8]=200; d.currentAmounts=new boolean[64]; d.currentAmounts[8]=true;
        return d;
    }
    @Test public void fluidThresholdSelectsBestInBoundsAndCanExportAllMatchingChunks() throws Exception {
        QueryFilter filter = new QueryFilter(); filter.id="oil"; filter.minimumFluid=150;
        var best = new ProspectingExport.Options(filter,false,1000);
        var points = ProspectingExport.points(fluid(),best,-128,-128,-1,-1);
        assertEquals(1,points.size()); assertEquals(-104,points.get(0).x()); assertEquals(-120,points.get(0).z());
        assertTrue(points.get(0).name().contains("200 L/Op [CURRENT]"));
        var partial = ProspectingExport.points(fluid(),best,-120,-120,-120,-104);
        assertEquals(1,partial.size()); assertEquals(-104,partial.get(0).z());
        assertTrue(partial.get(0).name().contains("150 L/Op [INITIAL]"));
        assertEquals(2,ProspectingExport.points(fluid(),new ProspectingExport.Options(filter,true,100),-128,-128,-1,-1).size());
    }
    @Test public void limitStopsFutureQueriesAndConditionsReachTheSource() throws Exception {
        QueryFilter filter = new QueryFilter(); filter.id="oil"; filter.minimumFluid=150;
        AtomicInteger calls = new AtomicInteger();
        var result = ProspectingExport.locate((x,z,sent) -> {
            calls.incrementAndGet(); assertSame(filter,sent); assertEquals(-512,x); assertEquals(-512,z);
            Tile tile=new Tile(); tile.deposits=List.of(fluid()); return tile;
        },new ProspectingExport.Options(filter,true,1),-128,-128,1024,1024,(done,total)->{});
        assertTrue(result.reachedLimit()); assertEquals(1,result.coordinates().size()); assertEquals(1,calls.get());
    }
    @Test public void oreConditionsUseExactIdsHeightOverlapRecordsAndDepletion() {
        QueryFilter f=new QueryFilter(); f.id="mix.iron"; f.minY=40; f.maxY=60; f.recordedOnly=true;
        Deposit d=new Deposit(); d.id="mix.iron"; d.name="Iron"; d.minY=20; d.maxY=40; d.source="RECORDED";
        assertTrue(f.accepts(d)); d.maxY=39; assertFalse(f.accepts(d)); d.maxY=40;
        d.source="PREDICTED"; assertFalse(f.accepts(d)); d.source="RECORDED";
        d.depleted=true; assertFalse(f.accepts(d)); f.includeDepleted=true; assertTrue(f.accepts(d));
        d.id="mix.iron2"; assertFalse(f.accepts(d));
    }
    @Test public void cancellationAndHugeRangeNeverIssueRequests() {
        AtomicInteger calls=new AtomicInteger();
        ProspectingExport.Source source=(x,z,f)->{calls.incrementAndGet(); return null;};
        var options=new ProspectingExport.Options(new QueryFilter(),false,1000);
        assertThrows(IllegalArgumentException.class,()->ProspectingExport.locate(source,options,-30000000,-30000000,30000000,30000000,(a,b)->{}));
        Thread.currentThread().interrupt();
        try { assertThrows(CancellationException.class,()->ProspectingExport.locate(source,options,0,0,100,100,(a,b)->{})); }
        finally { Thread.interrupted(); }
        assertEquals(0,calls.get());
    }
}
