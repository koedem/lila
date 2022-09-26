import * as xhr from 'common/xhr';

lichess.load.then(() => $('.ask-container').each((_, e: EleLoose) => new Ask(e.firstElementChild!)));

// i have yet to be accused me of proper OOP

class Ask {
  el: Element;
  submitEl?: Element;
  feedbackEl?: HTMLInputElement;

  constructor(askEl: Element) {
    this.el = askEl;
    wireExclusiveChoices(this);
    wireRankedChoices(this);
    this.feedbackEl = wireFeedback(this);
    this.submitEl = wireSubmit(this);
  }
  ranking = (): string => 
    Array.from($('.ranked-choice', this.el), e => e?.getAttribute('value')).join('-');

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
  $('.exclusive-choice', ask.el).on('click', function (e: Event) {
    const target = e.target as Element;
    const value = target.classList.contains('selected') ? '' : target?.getAttribute('value');
    xhr
      .text(`/ask/${ask.el.id}?picks=${value}`, { method: 'post' })
      .then((frag: string) => rewire(ask.el, frag));
  });
};

const wireRankedChoices = (ask: Ask): void => {

  const isBefore = (lhs: Element, rhs: Element | null): boolean => {
    if (lhs.parentElement != rhs?.parentElement) return false;
    for (let it = lhs.previousSibling; it; it = it.previousSibling) 
      if (it === rhs) return true;
    return false;
  };
  let dragging: Element | null = null;
  let after: Element | null = null;
  let initialOrder = ask.ranking();

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
      const newOrder = ask.ranking();
      if (newOrder == initialOrder) return;
      const path = `/ask/${ask.el.id}?picks=${newOrder}`;
      xhr.text(path, { method: 'post' }).then(() => { initialOrder = newOrder; });
    });
};

const wireFeedback = (ask: Ask): HTMLInputElement|undefined => {
  const feedbackEl = $('.feedback', ask.el)
    .on('input', () => 
      ask.setState(ask.feedbackEl?.value == initialFeedback ? 'clean' : 'dirty')
    )
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
  const initialFeedback = feedbackEl?.value;
  return feedbackEl;
};

const wireSubmit = (ask: Ask): Element|undefined => {
  const submitEl = $('.ask__submit', ask.el).get(0);
  $('input', submitEl).on('click', () => {
    const path = `/ask/feedback/${ask.el.id}`;
    xhr
      .text(path, {
        method: 'post', 
        body: ask.feedbackEl?.value && xhr.form({ text: ask.feedbackEl?.value })
      })
      .then(
        (frag: string) =>
          rewire(ask.el, frag)?.setState(ask.feedbackEl?.value ? 'success' : 'clean'),
        failure
      );
  });
  return submitEl
};
