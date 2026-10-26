// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Col, ConfirmButton, DataTable, ErrorBoundary, SourceTag } from './ui';

afterEach(cleanup);
type R = { name: string; n: number };
const cols: Col<R>[] = [{ key: 'name', head: 'Name' }, { key: 'n', head: 'N', num: true }];
const rows: R[] = Array.from({ length: 60 }, (_, i) => ({ name: 'row' + i, n: i }));

describe('DataTable', () => {
  it('paginates large data sets', () => {
    render(<DataTable cols={cols} rows={rows} empty="none" pageSize={25} />);
    expect(screen.getAllByRole('row')).toHaveLength(26);
    fireEvent.click(screen.getByText('Next'));
    expect(screen.getByText('Page 2 / 3')).toBeTruthy();
  });
  it('sorts numerically, toggling direction', () => {
    render(<DataTable cols={cols} rows={rows} empty="none" pageSize={5} filterable={false} />);
    fireEvent.click(screen.getByText('N'));
    expect(screen.getAllByRole('row')[1].textContent).toContain('row0');
    fireEvent.click(screen.getByRole('button', { name: /^N ▲/ }));
    expect(screen.getAllByRole('row')[1].textContent).toContain('row59');
  });
  it('shows the empty message', () => { render(<DataTable cols={cols} rows={[]} empty="No orders." />); expect(screen.getByText('No orders.')).toBeTruthy(); });
});

describe('components', () => {
  it('ConfirmButton requires a second confirmation', () => {
    let done = 0;
    render(<ConfirmButton label="Disconnect" confirmText="Sure?" onConfirm={() => done++} />);
    fireEvent.click(screen.getByText('Disconnect')); expect(done).toBe(0);
    fireEvent.click(screen.getByText('Confirm')); expect(done).toBe(1);
  });
  it('ErrorBoundary shows a recoverable error instead of a blank page', () => {
    const Boom = () => { throw new Error('kaput'); };
    const spy = console.error; console.error = () => {};
    render(<ErrorBoundary name="Page"><Boom /></ErrorBoundary>);
    console.error = spy;
    expect(screen.getByRole('alert').textContent).toContain('kaput'); expect(screen.getByText('Retry')).toBeTruthy();
  });
  it('SourceTag always names the data kind in text', () => { render(<SourceTag kind="SIMULATION" />); expect(screen.getByText(/SIMULATION/)).toBeTruthy(); });
});
