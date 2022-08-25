import * as xhr from 'common/xhr';

lichess.load.then(() => {
  const box = $('.twitch-box')
  box.on('click', 'a.twitch-sync', function (this: HTMLElement) {
    const twitchName = box.find('input').val()
    xhr.json(`/streamer/twitch/get/${twitchName}`).then((x) => alert(x));
  });
});