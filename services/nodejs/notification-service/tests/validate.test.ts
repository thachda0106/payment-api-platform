import { describe, it, expect } from 'vitest';
import { normalizeLedgerEvent, EventValidationError } from '../src/consumer';

const VALID = {
  id: '1b4e28ba-2fa1-11d2-883f-0016d3cca427',
  type: 'ledger.entry.committed',
  time: '2026-07-13T02:00:00.000Z',
  data: {
    payment_id: '9f8c7b6a-1234-4c5d-8e9f-001122334455',
    ledger_transaction_id: '3f8c7b6a-1234-4c5d-8e9f-001122334466',
    customer_id: 'c1',
    merchant_id: 'm1',
    amount: 9999,
    currency: 'USD',
  },
};

describe('normalizeLedgerEvent', () => {
  it('accepts a valid Avro/CloudEvents event', () => {
    const n = normalizeLedgerEvent({ ...VALID });
    expect(n.paymentId).toBe(VALID.data.payment_id);
    expect(n.customerId).toBe('c1');
    expect(n.amountMinor).toBe(9999);
  });

  it('rejects missing id', () => {
    const e: any = { ...VALID }; delete e.id;
    expect(() => normalizeLedgerEvent(e)).toThrow(EventValidationError);
  });

  it('rejects non-UUID payment_id', () => {
    expect(() => normalizeLedgerEvent({ ...VALID, data: { ...VALID.data, payment_id: 'nope' } })).toThrow(/payment_id/);
  });

  it('rejects missing customer_id', () => {
    const e: any = { ...VALID, data: { ...VALID.data } }; delete e.data.customer_id;
    expect(() => normalizeLedgerEvent(e)).toThrow(/customer_id/);
  });
});
