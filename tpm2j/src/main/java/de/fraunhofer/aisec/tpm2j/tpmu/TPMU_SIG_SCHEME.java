package de.fraunhofer.aisec.tpm2j.tpmu;

import de.fraunhofer.aisec.tpm2j.tools.ByteArrayable;

public interface TPMU_SIG_SCHEME extends ByteArrayable {

	/*
	 * TPMU_SIG_SCHEME Union
	 * typedef union {
	 *     TPMS_SCHEME_RSASSA    rsassa;
	 *     TPMS_SCHEME_RSAPSS    rsapss;
	 *     TPMS_SCHEME_ECDSA     ecdsa;
	 *     TPMS_SCHEME_ECDAA     ecdaa;
	 *     TPMS_SCHEME_ECSCHNORR ecSchnorr;
	 *     TPMS_SCHEME_HMAC      hmac;
	 *     TPMS_SCHEME_SIGHASH   any;
	 * } TPMU_SIG_SCHEME;
	 */
	
	public abstract byte[] toBytes();

	public abstract void fromBytes(byte[] source, int offset) throws Exception;
	
    public abstract String toString();	
}
