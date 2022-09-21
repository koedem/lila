import * as xhr from 'common/xhr';

lichess.load.then(() => $('.ask-container').each((_, e: EleLoose) => new Ask(e.firstElementChild!)));
class Ask {
  el: Element;
  submitEl?: Element;
  feedbackEl?: HTMLInputElement;
  isExclusive = false;
  isRanked = false;

  constructor(askEl: Element) {
    this.el = askEl;
    wireExclusiveChoices(this);
    wireRankedChoices(this);
    wireFeedback(this);
    wireSubmit(this);
  }
  ranking = (): string => Array.from($('.ranked-choice', this.el), e => e?.getAttribute('value')).join('-');

  setState = (state: 'clean' | 'dirty' | 'success') => {
    this.submitEl?.classList.remove('dirty', 'success');
    if (state != 'clean') this.submitEl?.classList.add(state);
  };
}

const rewire = (el: Element | null, frag: string): Ask | undefined => {
  while (el && !el.classList.contains('ask-container')) el = el.parentElement;
  if (el && frag) {
    el.innerHTML = frag;
    return new Ask(el.firstElementChild!);
  }
};

const failure = (reason: any): void => {
  console.log(`Ask XHR failed with: ${reason}`);
};

const wireExclusiveChoices = (ask: Ask): void => {
  ask.isExclusive =
    $('.xhr-choice', ask.el).on('click', function (e: Event) {
      const target = e.target as Element;
      const body = ask.feedbackEl && xhr.form({ text: ask.feedbackEl.value });
      const value = target.classList.contains('selected') ? '' : target?.getAttribute('value');
      xhr
        .text(`/ask/${ask.el.id}?picks=${value}`, { method: 'post', body: body })
        .then((frag: string) => rewire(ask.el, frag)?.setState('success'), failure);
    }).length > 0;
};

const wireRankedChoices = (ask: Ask): void => {
  const isBefore = (lhs: Element, rhs: Element | null): boolean => {
    if (lhs.parentElement != rhs?.parentElement) return false;
    for (let it = lhs.previousSibling; it; it = it.previousSibling) if (it === rhs) return true;
    return false;
  };
  let dragging: Element | null = null;
  let after: Element | null = null;
  ask.isRanked =
    $('.ranked-choice', ask.el)
      .on('dragstart', (e: DragEvent) => {
        dragging = e.target as Element;
        dragging.classList.add('dragging');
        after = dragging.nextElementSibling;
        e.dataTransfer!.effectAllowed = 'move';
        e.dataTransfer!.setData('text/plain', '');
      })
      .on('dragenter', (e: DragEvent) => {
        const target = e.target as Element;
        if (target.parentElement != dragging?.parentElement) return;
        const before = isBefore(dragging!, target) ? target : target?.nextSibling;
        target.parentNode?.insertBefore(dragging!, before);
      })
      .on('dragover', (e: DragEvent) => {
        e.preventDefault();
      })
      .on('dragend', () => {
        dragging?.classList.remove('dragging');
        dragging?.parentNode?.insertBefore(dragging, after);
      })
      .on('drop', () => {
        dragging?.classList.remove('dragging');
        dragging = null;
        ask.setState(ask.ranking() == initialOrder ? 'clean' : 'dirty');
      }).length > 0;
  const initialOrder = ask.ranking();
};

// optional feedbackEl text field
const wireFeedback = (ask: Ask): void => {
  ask.feedbackEl = $('.feedback', ask.el)
    .on('input', () => {
      console.log(ask.el);
      ask.setState(ask.feedbackEl?.value == initialFeedback ? 'clean' : 'dirty');
    })
    .on('keypress', (e: KeyboardEvent) => {
      if (
        e.key != 'Enter' ||
        e.shiftKey ||
        e.ctrlKey ||
        e.altKey ||
        e.metaKey ||
        !ask.submitEl?.classList.contains('dirty')
      )
        return;
      $('input', ask.submitEl).trigger('click');
      e.preventDefault();
    })
    .get(0) as HTMLInputElement;
  const initialFeedback = ask.feedbackEl?.value;
};

// submit button for feedback and/or ranked (non-xhr) choices
const wireSubmit = (ask: Ask): void => {
  ask.submitEl = $('.ask__submit', ask.el).get(0);
  $('input', ask.submitEl).on('click', () => {
    const path = `/ask/${ask.el.id}` + (ask.isRanked ? `?picks=${ask.ranking()}` : '');
    xhr
      .text(path, { method: 'post', body: xhr.form({ text: ask.feedbackEl?.value }) })
      .then((frag: string) => rewire(ask.el, frag)?.setState('success'), failure);
  });
};
