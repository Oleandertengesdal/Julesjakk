import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../lib/api';
import { adminRef, makeGame } from '../test/fixtures';
import { CorrectionNote, CorrectionPanel } from './Correction';

afterEach(() => vi.restoreAllMocks());

describe('CorrectionNote', () => {
  it('says nothing for an ordinary game', () => {
    const { container } = render(<CorrectionNote game={makeGame()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('tells everyone a result was corrected, by whom and why', () => {
    render(<CorrectionNote game={makeGame({ correction: { reason: 'Ola vant, ikke Kari', by: adminRef, at: '2026-11-15T10:00:00Z' } })} />);
    expect(screen.getByText(/Resultatet ble rettet av Admin/)).toBeInTheDocument();
    expect(screen.getByText(/Ola vant, ikke Kari/)).toBeInTheDocument();
  });

  it('marks an annulled game as not counting', () => {
    render(<CorrectionNote game={makeGame({ status: 'ANNULLED', correction: { reason: 'Registrert to ganger', by: adminRef, at: '2026-11-15T10:00:00Z' } })} />);
    expect(screen.getByText(/Annullert av Admin/)).toBeInTheDocument();
    expect(screen.getByText(/teller ikke/)).toBeInTheDocument();
  });
});

describe('CorrectionPanel', () => {
  it('needs a new result and a reason before it can save', async () => {
    render(<CorrectionPanel game={makeGame()} onChange={vi.fn()} />);
    const save = screen.getByRole('button', { name: 'Lagre rettelse' });
    expect(save).toBeDisabled();
    await userEvent.click(screen.getByLabelText('Svart vant'));
    expect(save).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Begrunnelse'), 'Feil vinner');
    expect(save).toBeEnabled();
  });

  it('sends the correction and shows the updated game', async () => {
    const updated = makeGame({ result: 'BLACK_WIN' });
    const post = vi.spyOn(api, 'post').mockResolvedValue(updated);
    const onChange = vi.fn();
    render(<CorrectionPanel game={makeGame()} onChange={onChange} />);
    await userEvent.click(screen.getByLabelText('Svart vant'));
    await userEvent.type(screen.getByLabelText('Begrunnelse'), 'Feil vinner');
    await userEvent.click(screen.getByRole('button', { name: 'Lagre rettelse' }));
    expect(post).toHaveBeenCalledWith('/api/admin/games/17/correct', { result: 'BLACK_WIN', reason: 'Feil vinner' });
    expect(onChange).toHaveBeenCalledWith(updated);
  });

  it('asks before annulling, and annuls with the reason', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(makeGame({ status: 'ANNULLED' }));
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
    render(<CorrectionPanel game={makeGame()} onChange={vi.fn()} />);
    await userEvent.type(screen.getByLabelText('Begrunnelse'), 'Registrert to ganger');
    await userEvent.click(screen.getByRole('button', { name: 'Annuller partiet' }));
    expect(post).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Annuller partiet' }));
    expect(post).toHaveBeenCalledWith('/api/admin/games/17/annul', { reason: 'Registrert to ganger' });
    expect(confirm).toHaveBeenCalledTimes(2);
  });

  it('shows why the server refused, for example a knockout game', async () => {
    vi.spyOn(api, 'post').mockRejectedValue(new ApiError(409, 'CHESS-GAME-010', 'Resultatet kan ikke endres her.', 'web-1'));
    render(<CorrectionPanel game={makeGame()} onChange={vi.fn()} />);
    await userEvent.click(screen.getByLabelText('Remis'));
    await userEvent.type(screen.getByLabelText('Begrunnelse'), 'Remis');
    await userEvent.click(screen.getByRole('button', { name: 'Lagre rettelse' }));
    expect(await screen.findByText(/Resultatet kan ikke endres her/)).toBeInTheDocument();
  });
});
