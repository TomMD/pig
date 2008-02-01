package org.apache.pig.backend.hadoop.executionengine;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Properties;

import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.executionengine.ExecPhysicalOperator;
import org.apache.pig.backend.executionengine.ExecPhysicalPlan;
import org.apache.pig.backend.local.executionengine.POVisitor;
import org.apache.pig.impl.physicalLayer.PhysicalOperator;
import org.apache.pig.impl.logicalLayer.OperatorKey;

public class MapRedPhysicalPlan implements ExecPhysicalPlan {
    private static final long serialVersionUID = 1;
    
    protected OperatorKey root;
    protected Map<OperatorKey, ExecPhysicalOperator> opTable;    
    
    MapRedPhysicalPlan(OperatorKey root,
                       Map<OperatorKey, ExecPhysicalOperator> opTable) {
        this.root = root;
        this.opTable = opTable;
    }
    
    @Override
    public Properties getConfiguration() {
        // TODO
        return null;
    }

    @Override
    public void updateConfiguration(Properties configuration)
        throws ExecException {
        // TODO
    }
             
    @Override
    public void explain(OutputStream out) {
        POVisitor lprinter = new POVisitor(new PrintStream(out));
        
        ((PhysicalOperator)opTable.get(root)).visit(lprinter, "");
    }
    
    @Override
    public Map<OperatorKey, ExecPhysicalOperator> getOpTable() {
    	return opTable;
    }
    
    @Override
    public OperatorKey getRoot() {
        return this.root;
    }
}

