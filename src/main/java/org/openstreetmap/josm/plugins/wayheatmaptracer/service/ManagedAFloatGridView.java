package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** FLOAT_L0_Q_V1: the actual native float L0, widened on read, with strict acquired-cell support. */
final class ManagedAFloatGridView implements NativeScalarGridView {
    private final ScalarIntensityField field;
    private final TileHeatmapSampler.TileMosaic mosaic;
    private final AttemptMemoryLedger.Owner owner;
    private final boolean[] acquired;
    private final int columns;
    ManagedAFloatGridView(ScalarIntensityField field,TileHeatmapSampler.TileMosaic mosaic,
            EmissionWorkBudget budget,AttemptMemoryLedger.Owner owner){
        this.field=field;this.mosaic=mosaic;this.owner=owner;columns=(mosaic.image().getWidth()+511)/512;
        int rows=(mosaic.image().getHeight()+511)/512;
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
        var allocation=owner.reserve(AttemptMemoryLedger.arrayBytes(Math.multiplyExact(columns,rows),1)+256);
        acquired=new boolean[Math.multiplyExact(columns,rows)];
        long firstX=(long)(mosaic.originWorldPxX()/512),firstY=(long)(mosaic.originWorldPxY()/512);
        if(mosaic.originWorldPxX()%512==0&&mosaic.originWorldPxY()%512==0)
        for(var tile:mosaic.tiles()){
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);
            long x=(long)tile.x()-firstX,y=(long)tile.y()-firstY;
            if(x>=0&&x<columns&&y>=0&&y<rows&&tile.usable()&&tile.color().equals(mosaic.color())
                    &&tile.zoom()==mosaic.zoom()&&tile.width()==512&&tile.height()==512
                    &&mosaic.tileImages().containsKey(tile.color()+"/z"+tile.zoom()+"-x"+tile.x()+"-y"+tile.y()+".png"))acquired[(int)(y*columns+x)]=true;
        }
        allocation.adopt(this);
    }
    private void requireOpen(){if(owner.isClosed())throw new IllegalStateException("Managed A float grid is closed");}
    public int width(){requireOpen();return field.width();}public int height(){requireOpen();return field.height();}
    public int originX(){requireOpen();return field.originX();}public int originY(){requireOpen();return field.originY();}
    private boolean acquired(int x,int y){
        int nx=x+field.originX(),ny=y+field.originY();
        return nx>=0&&ny>=0&&nx<mosaic.image().getWidth()&&ny<mosaic.image().getHeight()&&acquired[(ny/512)*columns+nx/512];
    }
    public double nodeValue(int x,int y){requireOpen();return field.isValid(x,y)&&acquired(x,y)?field.value(x,y):Double.NaN;}
    public boolean supportsCell(int x,int y){requireOpen();return field.supportsInterpolationCell(x,y)
        &&acquired(x,y)&&acquired(x+1,y)&&acquired(x,y+1)&&acquired(x+1,y+1);}
    public RasterPoint toGrid(RasterPoint p){requireOpen();return new RasterPoint(p.x()/mosaic.virtualRasterScale()-field.originX(),p.y()/mosaic.virtualRasterScale()-field.originY());}
    public RasterPoint toBaseRaster(RasterPoint p){requireOpen();return new RasterPoint((p.x()+field.originX())*mosaic.virtualRasterScale(),(p.y()+field.originY())*mosaic.virtualRasterScale());}
}
