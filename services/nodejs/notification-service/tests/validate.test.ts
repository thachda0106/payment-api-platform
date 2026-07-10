import { describe, it, expect } from 'vitest';
import { validateLedgerEvent, EventValidationError } from '../src/consumer';

const VALID = {
  eventId: '1b4e28ba-2fa1-11d2-883f-0016d3cca427',
  type: 'LedgerEntryCreated',
  paymentId: '9f8c7b6a-1234-4c5d-8e9f-001122334455',
  ledgerTransactionId: '3f8c7b6a-1234-4c5d-8e9f-001122334466',
  customerId: 'c1',
  amount: '99.99',
  timestamp: '2026-07-09T12:00:00.000Z',
};

describe('validateLedgerEvent', () => {
  it('accepts a valid event', () => {
    expect(() => validateLedgerEvent({ ...VALID })).not.toThrow();
  });

  it('rejects missing eventId', () => {
    const e: any = { ...VALID }; delete e.eventId;
    expect(() => validateLedgerEvent(e)).toThrow(EventValidationError);
  });

  it('rejects non-UUID paymentId', () => {
    expect(() => validateLedgerEvent({ ...VALID, paymentId: 'nope' })).toThrow(/paymentId/);
  });

  it('rejects non-positive amount', () => {
    expect(() => validateLedgerEvent({ ...VALID, amount: '0' })).toThrow(/amount/);
  });

  it('rejects missing customerId', () => {
    const e: any = { ...VALID }; delete e.customerId;
    expect(() => validateLedgerEvent(e)).toThrow(/customerId/);
  });
});
