package com.dp.nebula.wormhole.plugins.reader.greenplumreader;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import org.apache.commons.dbcp.DelegatingConnection;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyInputStream;
import org.postgresql.util.PSQLException;

import com.dp.nebula.wormhole.common.AbstractPlugin;
import com.dp.nebula.wormhole.common.DefaultLine;
import com.dp.nebula.wormhole.common.JobStatus;
import com.dp.nebula.wormhole.common.WormholeException;
import com.dp.nebula.wormhole.common.interfaces.ILine;
import com.dp.nebula.wormhole.common.interfaces.ILineSender;
import com.dp.nebula.wormhole.common.interfaces.IReader;
import com.dp.nebula.wormhole.plugins.common.DBSource;
import com.dp.nebula.wormhole.plugins.common.ErrorCodeUtils;

public class GreenplumReader extends AbstractPlugin implements IReader{	
	
	static final int PLUGIN_NO = 2;
	
	static final int ERROR_CODE_ADD = JobStatus.PLUGIN_BASE*PLUGIN_NO;

	private Connection conn;

	private String ip;

	private String port = "5432";

	private String dbname;
	
	private String sql;
	
	private String encoding = "utf-8";
	
	private Log logger = LogFactory.getLog(GreenplumReader.class);

	private int sucLineCounter = 0;
		
	private final static char ESC = '\\';
	
	private final static char DEP = '\t';
	
	private final static char BREAK = '\n';
	
	private final static int BUFFER_LENGTH = 8 * 1024*1024;

	@Override
	public void init() {
		/* for database connection */
		this.ip = getParam().getValue(ParamKey.ip,"");
		this.port = getParam().getValue(ParamKey.port, this.port);
		this.dbname = getParam().getValue(ParamKey.dbname,"");	
		this.sql = getParam().getValue(ParamKey.sql, "").trim();
		this.encoding = getParam().getValue(ParamKey.encoding,encoding).trim();
	}

	@Override
	public void connection() {
		try {
			conn = DBSource.getConnection(this.getClass(), ip, port, dbname);
		} catch (Exception e) {
			throw new WormholeException(e, JobStatus.READ_CONNECTION_FAILED.getStatus() + ERROR_CODE_ADD);
		}
	}
	
	private void fetchData(ILineSender sender, PGCopyInputStream inputStream) {
        byte[] bufferArray = new byte[BUFFER_LENGTH];
        ILine line = new DefaultLine();
    	StringBuffer field = new StringBuffer();
    	boolean escape = false;
           
        try {
        	int res = 0;
        	while((res=inputStream.read(bufferArray))!=0) {
        		String buffer = new String(bufferArray,0,res,this.encoding);
            	char[] charArray = buffer.toCharArray();
            	int size = charArray.length;
            	for(int i= 0;i < size;i++){
            		if(escape) {
        				escape = false;
            			if(charArray[i] == 'N') {
            				line.addField(null);
            				field = new StringBuffer();
            				if(i+1 < size) {
            					i++;
            				}
            			}
            			else {
            				field.append(charArray[i]);
            			}
        			} else if(charArray[i] == ESC) {
            			escape = true;
        			} else if(charArray[i] == DEP) {
            			line.addField(field.toString());
            			field = new StringBuffer();
            		} else if (charArray[i] == BREAK) {
            			this.sucLineCounter ++ ;
            			line.addField(field.toString());
            			sender.send(line);
            			line = new DefaultLine();
            			field = new StringBuffer();
            		} else {
            			field.append(charArray[i]);
            		}
            	}
        		bufferArray = new byte[BUFFER_LENGTH];
        	}
        	sender.flush();
        	getMonitor().increaseSuccessLine(sucLineCounter);
		} catch (Exception e) {
			logger.error("Reader copy data failed:",e);
			throw new WormholeException(e,JobStatus.READ_FAILED.getStatus());
		} 		
	}
	
	@Override
	public void read(ILineSender lineSender){
		PGCopyInputStream inputStream = null;
		if(sql.isEmpty()){
			logger.error("Sql for GreenplumReader is empty.");
			throw new WormholeException("Sql for GreenplumReader is empty.",JobStatus.READ_FAILED.getStatus()+ERROR_CODE_ADD);	
		}
		logger.info(String.format("GreenplumReader start to query %s .", sql));
		for(String sqlItem:sql.split(";")){
			sqlItem = sqlItem.trim();
			if(sqlItem.isEmpty()) {
				continue;
			}
			try {
				inputStream = new PGCopyInputStream((PGConnection) ((DelegatingConnection) conn).getInnermostDelegate(), sqlItem);
				fetchData(lineSender,inputStream);
			} catch (Exception e) {				
				logger.error("GreenplumReader error");
				WormholeException ex = new WormholeException(e,JobStatus.READ_FAILED.getStatus());
				if(e instanceof WormholeException) {
					ex = (WormholeException) e;
				}
				else if(e instanceof PSQLException) {
					ErrorCodeUtils.psqlReaderWrapper((PSQLException)e,ex);
				}
				ex.setStatusCode(ex.getStatusCode() + ERROR_CODE_ADD);
				throw ex;
			}
		}
	}

	@Override
	public void finish(){
		try {
			if (conn != null) {
				conn.close();
			}
			conn = null;
		} catch (SQLException e) {
			logger.error(e.toString(),e);
		}
	}
}
