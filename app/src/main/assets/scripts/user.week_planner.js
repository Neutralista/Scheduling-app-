var WeekPlanner = new Script({ id: 'user.week_planner', displayName: 'Week Planner' });

// ── date helpers ──────────────────────────────────────────────────────────────

function _wp_pad2(n) { return (n < 10 ? '0' : '') + n; }

function _wp_nextMondayDate() {
  var now = new Date();
  var dow = now.getDay();
  var toNextMon = (dow === 0) ? 1 : 8 - dow;
  return new Date(now.getFullYear(), now.getMonth(), now.getDate() + toNextMon);
}

function _wp_dateStr(mon, dayIdx) {
  var d = new Date(mon.getFullYear(), mon.getMonth(), mon.getDate() + dayIdx);
  return d.getFullYear() + '-' + _wp_pad2(d.getMonth() + 1) + '-' + _wp_pad2(d.getDate());
}

function _wp_fmtDate(dateStr) {
  var p = dateStr.split('-');
  var d = new Date(parseInt(p[0]), parseInt(p[1]) - 1, parseInt(p[2]));
  var m = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
  return _wp_pad2(d.getDate()) + ' ' + m[d.getMonth()];
}

var _DAY_FULL = ['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'];

// ── widget ────────────────────────────────────────────────────────────────────
//
// step 0          — idle card: single "Set next week →" button
// step 1-7, sub 0 — yesno dialog: "Work day?" with ← Back + Cancel + Day off buttons
// step 1-7, sub 1 — timerange dialog: shift hours with ← Back + Cancel + OK buttons

WeekPlanner.widget = function(state, signals) {
  var step    = Math.round(state.values['step']    || 0);
  var substep = Math.round(state.values['substep'] || 0);
  var mon = _wp_nextMondayDate();
  var weekRange = _wp_fmtDate(_wp_dateStr(mon, 0)) + ' – ' + _wp_fmtDate(_wp_dateStr(mon, 6));

  // ── idle ──────────────────────────────────────────────────────────────────
  if (step === 0) {
    return {
      title:       'Week Planner',
      subtitle:    weekRange,
      value:       'Next week',
      actionLabel: 'Set next week →',
      done:        false
    };
  }

  // ── wizard ────────────────────────────────────────────────────────────────
  var dayIdx  = step - 1;
  var dateStr = _wp_dateStr(mon, dayIdx);
  var dayName = _DAY_FULL[dayIdx];
  var dateFmt = _wp_fmtDate(dateStr);

  var sched      = signals.workSchedule.getScheduleForDate(dateStr);
  var shiftStart = (sched && sched.shiftStart) ? sched.shiftStart : '09:00';
  var shiftEnd   = (sched && sched.shiftEnd)   ? sched.shiftEnd   : '17:00';

  var view = {
    title:       'Next week  ·  Day ' + step + ' of 7',
    subtitle:    dayName + '  ' + dateFmt,
    actionLabel: '...',
    done:        false
  };

  // Determine back label: day 1 goes back to idle, otherwise previous day
  var backLabel = '← Back';

  if (substep === 0) {
    // yesno dialog — work or day off?
    view.dialog = {
      question:  'Is ' + dayName + ' (' + dateFmt + ') a work day?',
      type:      'yesno',
      yesLabel:  'Work day',
      noLabel:   'Day off',
      backLabel: backLabel
    };
  } else {
    // timerange dialog — shift hours
    view.dialog = {
      question:     'Shift hours for ' + dayName + ' (' + dateFmt + ')',
      type:         'timerange',
      placeholder:  shiftStart,
      placeholder2: shiftEnd,
      backLabel:    backLabel
    };
  }

  return view;
};

// ── onAction — primary button ─────────────────────────────────────────────────
// step 0: start wizard (opens yesno for day 1)

WeekPlanner.onAction = function(state, signals) {
  var step = Math.round(state.values['step'] || 0);
  if (step === 0) {
    state.values['step']    = 1;
    state.values['substep'] = 0;
  }
  return state;
};

// ── onAnswer — dialog responses ───────────────────────────────────────────────

WeekPlanner.onAnswer = function(state, answer, signals) {
  var step    = Math.round(state.values['step']    || 0);
  var substep = Math.round(state.values['substep'] || 0);
  if (step === 0) return state;

  var mon     = _wp_nextMondayDate();
  var dayIdx  = step - 1;
  var dateStr = _wp_dateStr(mon, dayIdx);

  if (answer === 'cancel') {
    // Cancel aborts the whole wizard
    state.values['step']    = 0;
    state.values['substep'] = 0;
    return state;
  }

  if (answer === 'back') {
    if (substep === 1) {
      // In timerange dialog: go back to yesno for the same day
      state.values['substep'] = 0;
    } else {
      // In yesno dialog: go back to previous day (or abort if on day 1)
      if (step > 1) {
        state.values['step']    = step - 1;
        state.values['substep'] = 0;
      } else {
        state.values['step']    = 0;
        state.values['substep'] = 0;
      }
    }
    return state;
  }

  if (substep === 0) {
    // ── yesno response ───────────────────────────────────────────────────────
    if (answer === 'no') {
      // Day off — save and advance
      signals.workSchedule.setScheduleForDate(dateStr, { isWork: false });
      state.values['step']    = (step < 7) ? step + 1 : 0;
      state.values['substep'] = 0;
    } else {
      // Work day — open time picker
      state.values['substep'] = 1;
    }
  } else {
    // ── timerange response — "HH:mm|HH:mm" ──────────────────────────────────
    var parts = answer.split('|');
    signals.workSchedule.setScheduleForDate(dateStr, {
      isWork:     true,
      shiftStart: parts[0] || '09:00',
      shiftEnd:   parts[1] || '17:00'
    });
    state.values['step']    = (step < 7) ? step + 1 : 0;
    state.values['substep'] = 0;
  }

  return state;
};

WeekPlanner;
